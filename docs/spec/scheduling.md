# Post: queue and scheduled sending

Status: draft. Requirements R24–R30 of [product.md](product.md) refer here.

Decisions of 2026-11-15: the schedule is a set of weekly slots; scheduled posts are sent by the device for every network, including Mastodon. The app does not use Mastodon's `scheduled_statuses`, so all networks behave the same and a queued post stays editable until it is sent.

## Schedule

- S1. The schedule is a list of times for each weekday, for example Monday–Friday 09:00, 13:00, 18:00 and Saturday 11:00. An empty schedule is allowed; then "Next free slot" is unavailable and Send later asks for a time.
- S2. Times are local wall-clock times in the device time zone. A slot that does not exist on a daylight-saving change day is moved to the first minute after the gap; a slot that occurs twice is used once, at its first occurrence. A slot-based post stores the local date and time of its slot and is converted to an instant in the time zone the device has when the alarm is set, so it follows the device to a new zone. A post with an explicit time stores an absolute instant.
- S3. A slot is free when no queued post is assigned to it. Each slot holds at most one post.
- S4. "Next free slot" assigns the earliest free slot that is at least one minute after now.
- S5. A post may have an explicit time instead of a slot. Such posts do not occupy slots and may share a time with other posts.
- S6. Changing the schedule does not move posts that already have a slot time: each queued post keeps its assigned time. The Schedule screen offers "Redistribute", which reassigns all slot-based posts, in their current order, to the free slots of the new schedule.
- S7. Deleting or sending a slot-based post early frees its slot; later posts stay where they are.
- S8. The user changes the order of slot-based posts by dragging them in the Queue tab. The set of slot times held by these posts stays the same; after a drop the posts take those times again, earliest time to the first post in the new order. Example: A at 09:00, B at 13:00, C at 18:00; dragging C to the top gives C 09:00, A 13:00, B 18:00.
- S9. Posts with an explicit time do not move by dragging and keep their time; slot-based posts may be dragged past them. A post whose delivery has started, and posts in the Late and Failed groups, cannot be dragged.
- S10. After a drop the Queue tab shows "Order changed" with Undo for a few seconds. For screen readers and as an alternative to dragging, the row menu has Move up and Move down, which follow the same rule.

## Queue

- Q1. A queued post has a send time, a list of target accounts and a state per delivery: waiting, sending, sent, failed.
- Q2. A queued post can be edited until its first delivery starts. Editing re-runs the limit checks for its targets.
- Q3. Send now on a queued post starts delivery immediately and frees its slot.
- Q4. A post is late when its send time passed more than 30 minutes ago and its first delivery has not started. Typical causes: the phone was off, had no network, or Android delayed the job. The threshold is a setting under Sending (15 minutes, 30 minutes, 1 hour, 3 hours). A post that is due but not late is sent as usual.
- Q5. Post never sends a late post on its own. The job puts it on hold, and holding frees its slot. The check runs whenever the job starts: after boot (M3), when the network returns, and on the periodic check (M4).
- Q6. When posts go on hold, a notification says "N posts missed their time" with three actions:
  - Send now: sends all held posts at once, in queue order (Q8).
  - Reschedule: gives each held post, in queue order, the next free slot. A post with an explicit time also gets a slot.
  - Review: opens the Late group of the Queue tab.
  The app does not cancel the notification until a decision is made, and updates it when more posts go on hold. Android 14 and later let the user swipe it away; the Late group and the badge (Q7) stay.
- Q7. The Late group sits at the top of the Queue tab. It has the same three actions for the whole group. Each post in it can also be sent now, given a new time, edited or deleted. The composer's top bar shows a warning badge on Posts while the group is not empty.
- Q8. Several posts due at the same time are sent in their queue order, one after another per account, so threads and order stay intact.

## Android mechanism

Facts are from developer.android.com, read 2026-11-15:

- WorkManager's `setInitialDelay` is a minimum delay, and Doze defers it; it cannot hit a chosen minute.
- `AlarmManager.setExactAndAllowWhileIdle` fires in Doze, at most once per 9 minutes per app while idle.
- `SCHEDULE_EXACT_ALARM` is not granted to new installs of apps that target Android 13+. `USE_EXACT_ALARM` is granted at install and cannot be revoked; its restriction is a Google Play policy, which does not apply to an app distributed through Obtainium.
- Alarms are lost on reboot and must be set again on `BOOT_COMPLETED`.
- The documentation recommends starting longer work from the alarm's receiver with WorkManager.

Design:

- M1. One exact alarm is set for the earliest pending send time, with `USE_EXACT_ALARM`, as an alarm clock (`setAlarmClock`; why, see M7). Whenever the queue changes, the alarm is reset to the new earliest time. The system may show the alarm icon in the status bar and the time of the next post where it shows the next alarm; a tap on that time opens the Queue tab. When the system refuses exact alarms, the app sets an inexact alarm (`setAndAllowWhileIdle`) and a WorkManager run delayed to the same time.
- M2. The alarm receiver enqueues a unique WorkManager job with a `CONNECTED` network constraint as expedited work (`RUN_AS_NON_EXPEDITED_WORK_REQUEST` when the quota is exhausted), because Doze does not run ordinary jobs. The job sends every post whose time has come, then sets the next alarm. A job that uploads video calls `setForeground` at its start, inside the window the alarm grants.
- M3. On `BOOT_COMPLETED`, time zone change, time change and app update, the app sets the alarm again. Then the job runs once to put late posts on hold and ask about them (Q4–Q6).
- M4. A periodic WorkManager check every 30 minutes restores a missing alarm or job, as in ShareDing.
- M5. Video uploads run as a long-running worker with a `dataSync` foreground notification. (Hypothesis, not verified: starting the foreground worker from an exact-alarm receiver on Android 14+ is allowed; check on a device before relying on it.)
- M6. Without network at send time, the job waits for the network constraint. When the network returns after the late threshold, the post goes on hold instead (Q5). Retries of failed deliveries use WorkManager backoff starting at 30 seconds.
- M7. The alarm is an alarm clock always, not a setting (decision of 2026-11-16, #10): a scheduler that sends late defeats its purpose, and the alarm icon is an acceptable cost. Reason, measured 2026-11-16 on a OnePlus CPH2747 with Android 17 and Post 0.1.0-rc.1: `setExactAndAllowWhileIdle` under `USE_EXACT_ALARM` got `exactAllowReason=policy_permission` and still the window of an inexact alarm in `adb shell dumpsys alarm`, 75 % of the time left until it is due (`window=+14m47s926ms` for a post queued at 15:40:29 for 16:00), at most one hour. Such a delay is beyond the late threshold (Q4), and the post would go on hold. Lists measured the same on this phone with Android 16 (`juev/lists` R119): only `setAlarmClock` was delivered on time there, and the Lists alarm clocks on Android 17 show `window=0`.

## Verification scenarios

- With slots Mon 09:00 and 13:00, queue three posts on Monday at 08:00: they get 09:00, 13:00 and the next weekday's first slot.
- Queue a post for 3 minutes ahead, put the device into Doze (`adb shell dumpsys deviceidle force-idle`): it is sent within a minute of its time.
- Queue A 09:00, B 13:00 (slots) and X 11:00 (explicit time). Drag B above A: B gets 09:00, A 13:00, X stays at 11:00. Undo restores A 09:00, B 13:00.
- Reboot with a queued post due in an hour: the alarm is restored and the post is sent on time.
- Turn the phone off before two posts are due and on two hours later: nothing is sent; one notification "2 posts missed their time" appears. Reschedule puts them into the next two free slots in their order; Send now sends both in order.
- Turn off network at send time and on again after 10 minutes: the post is sent. After 2 hours: it goes on hold and the notification appears.
- Change the late threshold to 3 hours and repeat the two-hour case: the posts are sent without asking.
- Kill the process during a thread: the thread resumes from the first unpublished part without duplicates.
