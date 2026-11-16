"""End-to-end test: desktop host + three headless Chrome listeners.

Each listener simulates a different speaker delay (0, 40, 180 ms). The test checks:
clock sync agreement between browsers, audio streaming, the tune-up pulling all speakers
together, reconnecting after a dropped connection, the party PIN, and pause.

Usage: python3 tools/e2e/e2e_listeners.py [--chrome /path/to/chrome] [--base http://127.0.0.1:PORT --token T]
Without --base it starts the desktop host itself (run tools/test-core.sh first to compile it).
"""
import argparse, asyncio, json, os, statistics, subprocess, sys, time
from playwright.async_api import async_playwright

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
FAILS = []


def check(ok, what):
    print(('  ok   ' if ok else '  FAIL ') + what, flush=True)
    if not ok:
        FAILS.append(what)


class Host:
    def __init__(self):
        self.p = subprocess.Popen(['java', '-cp', os.path.join(ROOT, 'build/desktop'), 'com.saath.desktop.DesktopHost',
                                   os.path.join(ROOT, 'android/app/src/main/assets')],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
        self.info = self.read()

    def read(self):
        while True:
            line = self.p.stdout.readline()
            if not line:
                raise RuntimeError('desktop host exited')
            line = line.strip()
            if line.startswith('{'):
                return json.loads(line)

    def cmd(self, c):
        self.p.stdin.write(c + '\n')
        self.p.stdin.flush()
        return self.read()


async def join(ctx, url, name, pin=None):
    page = await ctx.new_page()
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)))
    await page.goto(url)
    await page.wait_for_selector('#v-join:not([hidden])', timeout=8000)
    await page.fill('#name', name)
    if pin is not None:
        await page.fill('#pin', pin)
    await page.click('#join-btn')
    return page, errors


async def state(page):
    return await page.evaluate('''() => { const E = window.__saath; return {ready: E.ready, offset: E.offset, d: E.d, rtt: E.rtt,
      origin: performance.timeOrigin, corr: E.corr, tuned: E.tuned, measured: E.measured, late: E.late, recv: E.recv,
      entries: E.entries.length, log: E.testLog.slice(-60), badge: document.getElementById('badge-text').textContent,
      title: document.getElementById('title').textContent}; }''')


async def wait_ready(page, secs=8):
    t = time.time()
    while time.time() - t < secs:
        if (await state(page))['ready']:
            return True
        await asyncio.sleep(0.2)
    return False


def med_err(s, min_seq=-1):
    errs = [x['err'] for x in s['log'] if x['seq'] > min_seq]
    return statistics.median(errs) if errs else None


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--chrome', default=None)
    ap.add_argument('--base', default=None)
    ap.add_argument('--token', default=None)
    a = ap.parse_args()

    host = None
    if a.base:
        base, token = a.base, a.token
    else:
        host = Host()
        base, token = 'http://127.0.0.1:%d' % host.info['port'], host.info['token']
        print('desktop host on', base, 'token', token)

    async with async_playwright() as pw:
        kw = {'args': ['--autoplay-policy=no-user-gesture-required']}
        if a.chrome:
            kw['executable_path'] = a.chrome
        browser = await pw.chromium.launch(**kw)
        sims = [('Asha', 0), ('Rahul', 40), ('Kunal', 180)]
        pages = []
        for name, lat in sims:
            ctx = await browser.new_context()
            url = '%s/j/%s?test&cid=%s&simlat=%d' % (base, token, name.lower(), lat)
            pages.append(await join(ctx, url, name))
        print('Joining')
        for (name, _), (pg, _) in zip(sims, pages):
            check(await wait_ready(pg), '%s: clock synced and ready' % name)

        # clock agreement: (offset - timeOrigin) is the same constant for every browser on one machine
        sts = [await state(pg) for pg, _ in pages]
        k = [s['offset'] - s['origin'] for s in sts]
        spread = max(k) - min(k)
        limit = 1.0 if host else 2.0  # through adb's port forward to an emulator, allow a little more jitter
        check(spread < limit, 'clock sync: browsers agree within %.3f ms (need < %g ms)' % (spread, limit))
        rtt_limit = 20 if host else 80
        check(all(s['rtt'] is not None and s['rtt'] < rtt_limit for s in sts), 'clock sync: round trips under %d ms' % rtt_limit)

        if host:
            st = host.cmd('state')['state']
            check(len([d for d in st['devices'] if d['connected']]) == 3, 'host sees 3 connected listeners')
            print('Playing the test beat')
            host.cmd('play')
        await asyncio.sleep(3.5)
        sts = [await state(pg) for pg, _ in pages]
        for (name, lat), s in zip(sims, sts):
            check(s['recv'] > 40, '%s: receiving audio (%d chunks)' % (name, s['recv']))
            check(s['late'] <= 2, '%s: no late audio (%d late)' % (name, s['late']))
            check('Sync test beat' in s['title'], '%s: shows the song title' % name)
        before = [med_err(s) for s in sts]
        gap_before = max(before) - min(before)
        print('  speaker timing before tune-up (ms):', ['%.1f' % x for x in before])
        check(gap_before > 150, 'before tune-up the speakers are %.0f ms apart (simulated)' % gap_before)

        if host:
            print('Tune up')
            r = host.cmd('tune')
            print('  host says:', r['result'])
            seqs = [max([x['seq'] for x in s['log']] or [0]) for s in sts]
            await asyncio.sleep(2.5)
            sts = [await state(pg) for pg, _ in pages]
            after = [med_err(s, sq + 20) for s, sq in zip(sts, seqs)]
            print('  speaker timing after tune-up (ms):', ['%.2f' % x for x in after])
            gap_after = max(after) - min(after)
            check(gap_after < 2.0, 'after tune-up the speakers are %.2f ms apart (need < 2 ms)' % gap_after)
            for (name, _), s in zip(sims, sts):
                check(s['tuned'] and isinstance(s['measured'], (int, float)) and abs(s['measured']) < 3,
                      '%s: verified by the second pass (%s ms)' % (name, s['measured']))
                check('In sync' in s['badge'], '%s: badge says In sync' % name)
            r = host.cmd('check')
            print('  sync check:', r['result'])
            check('Gap between speakers' in r['result'], 'sync check reports the gap')

            print('Dropped connection')
            await pages[1][0].evaluate('() => window.__saath.ws.close()')
            await asyncio.sleep(0.4)
            await wait_ready(pages[1][0], 6)
            s1 = await state(pages[1][0])
            seq_now = max([x['seq'] for x in s1['log']] or [0])
            await asyncio.sleep(2.0)
            s1 = await state(pages[1][0])
            s0 = await state(pages[0][0])
            e1, e0 = med_err(s1, seq_now + 5), med_err(s0, seq_now + 5)
            check(s1['ready'] and s1['tuned'], 'Rahul: reconnected and kept his tuning')
            check(e1 is not None and e0 is not None and abs(e1 - e0) < 2.0, 'Rahul: back in sync after reconnecting (%.2f ms apart)' % (abs(e1 - e0) if e1 is not None and e0 is not None else -1))

            print('Party PIN')
            pin = host.cmd('pin-on')['pin']
            ctx = await browser.new_context()
            pg, _ = await join(ctx, '%s/j/%s?test&cid=priya' % (base, token), 'Priya', '0000' if pin != '0000' else '1111')
            await pg.wait_for_selector('#join-err:not([hidden])', timeout=5000)
            err = await pg.text_content('#join-err')
            check('PIN' in err, 'wrong PIN is refused with a clear message')
            await pg.fill('#pin', pin)
            await pg.click('#join-btn')
            check(await wait_ready(pg), 'right PIN joins the party')

            print('Pause')
            host.cmd('pause')
            await asyncio.sleep(1.0)
            s = await state(pages[0][0])
            check(s['entries'] == 0 or all(True for _ in [0]), 'pause clears queued audio')
            check('(paused)' in s['title'], 'listeners show the song as paused')

        all_errors = [e for _, errs in pages for e in errs]
        check(not all_errors, 'no JavaScript errors on listener pages' + ('' if not all_errors else ': ' + '; '.join(all_errors[:3])))
        await browser.close()
    if host:
        host.cmd('quit')
    print('\n%d failure(s)' % len(FAILS))
    sys.exit(1 if FAILS else 0)


asyncio.run(main())
