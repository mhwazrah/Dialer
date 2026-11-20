# Full-code review brief (shared by all reviewers)

Project: Android dialer "MHWDialer" (fork of Goodwy Dialer), Kotlin + XML Views, package com.goodwy.dialer.
Source root: C:\Users\moham\Documents\projects\Right Dialer\app\src\main\kotlin\com\goodwy\dialer
Layouts: C:\Users\moham\Documents\projects\Right Dialer\app\src\main\res\layout
The com.goodwy.commons library is a binary dependency (source: github Goodwy/Goodwy-Commons @ b7b6ef84d2; known facts:
MyContactsContentProvider.getContacts(ctx, cursor) closes the cursor with use{}; getMyContactsCursor() returns null when
the Goodwy Contacts app is absent; ContactsHelper.getContacts() runs on its own background thread and calls back on it).

## Rules
- READ EVERY LINE of every file assigned to you (use Read with offset/limit for files > 500 lines, consecutive ranges,
  no gaps). Do not sample. At the end of your findings file list each file with "lines read: 1-N".
- Do NOT modify any project file. Only write your findings file.
- Review for ALL of these, small issues included (the owner wants every issue fixed, even minor):
  1. Memory leaks: static/companion refs to Activity/View/Context/Call, listeners/callbacks/receivers/observers/
     EventBus/sensors/Handlers/Runnables/timers/coroutines not removed, background callbacks touching destroyed
     activities, cursors/streams not closed, unbounded caches, bitmaps.
  2. Performance: main-thread I/O (ContentResolver, prefs-heavy loops, bitmap decode), duplicate/repeated loads,
     N+1 lookups, work per RecyclerView bind or per frame (pre-draw/global-layout listeners), notifyDataSetChanged,
     allocations/regex/formatters in loops, ARBITRARY DELAYS (postDelayed/sleep used as a workaround with no real
     need), deprecated APIs with perf cost (Handler(), AsyncTask), redundant view inflation/layout passes.
  3. Integration with Android CallLog / ContactsContract / BlockedNumberContract / Telecom: missing ContentObservers,
     stale data, projection/selection/limit issues, read/new flags, lookup by number vs normalized/PhoneLookup,
     contact id vs lookup key, permission revocation handling, SecurityException handling, missing data on
     insert/restore, notification consistency.
  4. Correctness bugs you notice while reading (data loss, crashes, races, wrong thread UI updates).
- Each finding must be verified: quote the exact offending line(s).

## Output
Write the full findings to the file path given in your task, as markdown, one finding per entry:
`### <ID> [High|Med|Low] [leak|perf|integration|correctness] file.kt:LINE`
`Evidence:` quoted code
`Problem:` 1-2 lines
`Fix:` concrete change, 1-3 lines
Then the "lines read" coverage list.
Your final reply to the caller: <= 300 words: counts by severity/category, the top 5 findings in one line each, and
the findings file path.
