package com.saath.app;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;

/** The songs on this phone, from Android's media library. */
final class Library {
  static final class Song {
    final String id, title, artist;
    final long durMs;
    final Uri uri;

    Song(String id, String title, String artist, long durMs, Uri uri) {
      this.id = id;
      this.title = title;
      this.artist = artist;
      this.durMs = durMs;
      this.uri = uri;
    }
  }

  static final Song BEAT = new Song("beat", "Sync test beat", "Built in · sharp clicks make any echo easy to hear",
      (long) com.saath.core.TestBeatSource.durationMs(), null);

  private Library() {}

  static List<Song> load(Context c) {
    List<Song> out = new ArrayList<>();
    out.add(BEAT);
    String[] proj = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION};
    try (Cursor cur = c.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
        MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " + MediaStore.Audio.Media.DURATION + " > 20000", null,
        MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
      if (cur == null) return out;
      while (cur.moveToNext()) {
        long id = cur.getLong(0);
        String title = cur.getString(1);
        String artist = cur.getString(2);
        if (artist == null || artist.equals("<unknown>")) artist = "";
        out.add(new Song(String.valueOf(id), title == null ? "Untitled" : title, artist, cur.getLong(3),
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)));
      }
    } catch (Exception ignored) {
      // no permission yet
    }
    return out;
  }
}
