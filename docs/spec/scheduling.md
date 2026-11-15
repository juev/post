# Post: queue and scheduled sending

Status: draft. Requirements R24–R30 of [product.md](product.md) refer here.

Decisions of 2026-11-15: the schedule is a set of weekly slots; scheduled posts are sent by the device for every network, including Mastodon. The app does not use Mastodon's `scheduled_statuses`, so all networks behave the same and a queued post stays editable until it is sent.

## Schedule

- S1. The schedule is a list of times for each weekday, for example Monday–Friday 09:00, 13:00, 18:00 and Saturday 11:00. An empty schedule is allowed; then "Next free slot" is unavailable and Send later asks for a time.
- S2. Times are local wall-clock times in the device time zone. A slot that does not exist on a daylight-saving change day is moved to the first minute after the gap; a slot that occurs twice is used once, at its first occurrence.
- S3. A slot is free when no queued post is assigned to it. Each slot holds at most one post.
- S4. "Next free slot" assigns the earliest free slot that is at least one minute after now.
- S5. A post may have an explicit time instead of a slot. Such posts do not occupy slots and may share a time with other posts.
- S6. Changing the schedule does not move posts that already have a slot time: each queued post keeps its assigned time. The Queue screen offers "Redistribute", which reassigns all slot-based posts, in their current order, to the free slots of the new schedule.
- S7. Deleting or sending a slot-based post early frees its slot; later posts stay where they are.

## Queue

- Q1. A queued post has a send time, a list of target accounts and a state per delivery: waiting, sending, sent, failed.
- Q2. A queued post can be edited until its first delivery starts. Editing re-runs the limit checks for its targets.
- Q3. Send now on a queued post starts delivery immediately and frees its slot.
- Q4. If the device was off or without network at the send time, the post is sent as soon as possible after it. The Queue screen shows that it is late. There is no expiry: a late post is still sent. (Open question: a setting to skip posts that are more than N hours late.)
- Q5. Several posts due at the same time are sent in their queue order, one after another per account, so threads and order stay intact.

## Android mechanism

Facts are from developer.android.com, read 2026-11-15:

- WorkManager's `setInitialDelay` is a minimum delay, and Doze defers it; it cannot hit a chosen minute.
- `AlarmManager.setExactAndAllowWhileIdle` fires in Doze, at most once per 9 minutes per app while idle.
- `SCHEDULE_EXACT_ALARM` is not granted to new installs of apps that target Android 13+. `USE_EXACT_ALARM` is granted at install and cannot be revoked; its restriction is a Google Play policy, which does not apply to an app distributed through Obtainium.
- Alarms are lost on reboot and must be set again on `BOOT_COMPLETED`.
- The documentation recommends starting longer work from the alarm's receiver with WorkManager.

Design:

- M1. One exact alarm is set for the earliest pending send time, with `USE_EXACT_ALARM`. Whenever the queue changes, the alarm is reset to the new earliest time.
- M2. The alarm receiver enqueues a unique WorkManager job with a `CONNECTED` network constraint. The job sends every post whose time has come, then sets the next alarm.
- M3. On `BOOT_COMPLETED`, time zone change, time change and app update, the app sets the alarm again. Posts whose time passed while the device was off are sent by the job (Q4).
- M4. A periodic WorkManager check every 30 minutes restores a missing alarm or job, as in ShareDing.
- M5. Video uploads run as a long-running worker with a `dataSync` foreground notification. (Hypothesis, not verified: starting the foreground worker from an exact-alarm receiver on Android 14+ is allowed; check on a device before relying on it.)
- M6. Without network at send time, the job waits for the network constraint. Retries of failed deliveries use WorkManager backoff starting at 30 seconds.

## Verification scenarios

- With slots Mon 09:00 and 13:00, queue three posts on Monday at 08:00: they get 09:00, 13:00 and the next weekday's first slot.
- Queue a post for 3 minutes ahead, put the device into Doze (`adb shell dumpsys deviceidle force-idle`): it is sent within a minute of its time.
- Reboot with a queued post: the alarm is restored; a post due during the reboot is sent after boot.
- Turn off network at send time: the post is sent when network returns and is shown as late until then.
- Kill the process during a thread: the thread resumes from the first unpublished part without duplicates.
