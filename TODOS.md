# TODOS

## Global uncaught exception handler
**What:** Add `Thread.setDefaultUncaughtExceptionHandler` in `App.java` to show a JavaFX error dialog instead of crashing with a raw stacktrace.
**Why:** DB connection failures (file locked, disk full) currently propagate as uncaught JDBI exceptions — user sees a stacktrace.
**Pros:** Better UX on infrastructure failures; ~15 lines.
**Cons:** Minimal effort.
**Context:** All business-logic errors already show Alert dialogs. This only covers unexpected runtime failures (DB, I/O). Add in `App.start()` before any other initialization.
**Depends on:** Nothing — can be done standalone.
**Added:** 2026-03-18 (eng review)
