package com.fastaccess.provider.tasks.notification;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;

import com.annimon.stream.Stream;
import com.fastaccess.R;
import com.fastaccess.data.dao.model.Comment;
import com.fastaccess.data.dao.model.Login;
import com.fastaccess.data.dao.model.Notification;
import com.fastaccess.helper.AppHelper;
import com.fastaccess.helper.InputHelper;
import com.fastaccess.helper.PrefGetter;
import com.fastaccess.helper.RxHelper;
import com.fastaccess.helper.ViewHelper;
import com.fastaccess.provider.rest.RestProvider;
import com.firebase.jobdispatcher.Constraint;
import com.firebase.jobdispatcher.FirebaseJobDispatcher;
import com.firebase.jobdispatcher.GooglePlayDriver;
import com.firebase.jobdispatcher.Job;
import com.firebase.jobdispatcher.JobParameters;
import com.firebase.jobdispatcher.JobService;
import com.firebase.jobdispatcher.Lifetime;
import com.firebase.jobdispatcher.RetryStrategy;
import com.firebase.jobdispatcher.Trigger;

import java.util.List;

import rx.schedulers.Schedulers;

/**
 * Created by Kosh on 19 Feb 2017, 6:32 PM
 */

public class NotificationSchedulerJobTask extends JobService {
    private final static String EVERY_30_MINS = "every_30_mins";
    private final static int THIRTY_MINUTES = 30 * 60;//in seconds
    private static final String NOTIFICATION_GROUP_ID = "FastHub";

    @Override public boolean onStartJob(JobParameters job) {
        if (Login.getUser() != null) {
            RestProvider.getNotificationService()
                    .getNotifications(0)
                    .subscribeOn(Schedulers.io())
                    .subscribe(item -> {
                        AppHelper.cancelAllNotifications(getApplicationContext());
                        if (item != null) {
                            onSave(item.getItems());
                        }
                    }, Throwable::printStackTrace);
        }
        return false;
    }

    @Override public boolean onStopJob(JobParameters job) {
        return false;
    }

    public static void scheduleJob(@NonNull Context context) {
        int duration = PrefGetter.getNotificationTaskDuration(context);
        scheduleJob(context, duration == 0 ? THIRTY_MINUTES : duration, false);
    }

    public static void scheduleJob(@NonNull Context context, int duration, boolean cancel) {
        FirebaseJobDispatcher dispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(context));
        if (cancel) dispatcher.cancel(EVERY_30_MINS);
        if (duration == -1) {
            dispatcher.cancel(EVERY_30_MINS);
            return;
        }
        duration = duration <= 0 ? THIRTY_MINUTES : duration;
        Job.Builder builder = dispatcher
                .newJobBuilder()
                .setTag(EVERY_30_MINS)
                .setRetryStrategy(RetryStrategy.DEFAULT_LINEAR)
                .setLifetime(Lifetime.FOREVER)
                .setRecurring(true)
                .setConstraints(Constraint.ON_ANY_NETWORK)
                .setTrigger(Trigger.executionWindow(10, duration))
                .setService(NotificationSchedulerJobTask.class);
        dispatcher.mustSchedule(builder.build());
    }

    private void onSave(@Nullable List<Notification> notificationThreadModels) {
        if (notificationThreadModels != null) {
            RxHelper.safeObservable(Notification.save(notificationThreadModels)).subscribe();
            onNotifyUser(notificationThreadModels);
        }
    }

    private void onNotifyUser(@NonNull List<Notification> notificationThreadModels) {
        long count = Stream.of(notificationThreadModels)
                .filter(Notification::isUnread)
                .count();
        if (count == 0) {
            AppHelper.cancelAllNotifications(getApplicationContext());
            return;
        }
        Context context = getApplicationContext();
        Bitmap largeIcon = BitmapFactory.decodeResource(context.getResources(),
                R.mipmap.ic_launcher);
        int accentColor = ViewHelper.getAccentColor(context);
        Notification firstNotification = notificationThreadModels.get(0);
        android.app.Notification grouped = getSummaryGroupNotification(firstNotification, accentColor, largeIcon);
        showNotification((int) firstNotification.getId(), grouped);
        Stream.of(notificationThreadModels)
                .filter(notification -> notification.isUnread() && notification.getId() != firstNotification.getId())
                .limit(10)
                .forEach(thread -> {
                    if (!InputHelper.isEmpty(thread.getSubject().getLatestCommentUrl())) {
                        RestProvider.getNotificationService().getComment(thread.getSubject().getLatestCommentUrl())
                                .subscribeOn(Schedulers.io())
                                .subscribe(comment -> {
                                    android.app.Notification toAdd = getNotificationWithComment(context, largeIcon, accentColor, thread, comment);
                                    showNotification((int) thread.getId(), toAdd);
                                }, Throwable::printStackTrace);
                    } else {
                        showNotificationWithoutCommnet(context, largeIcon, accentColor, thread);
                    }
                });
    }

    private void showNotificationWithoutCommnet(Context context, Bitmap largeIcon, int accentColor, Notification thread) {
        android.app.Notification toAdd = getNotification(thread.getSubject().getTitle(),
                thread.getRepository().getFullName())
                .setLargeIcon(largeIcon)
                .setContentIntent(getPendingIntent(thread.getId(), thread.getSubject().getUrl()))
                .setGroup(NOTIFICATION_GROUP_ID)
                .setColor(accentColor)
                .addAction(R.drawable.ic_github, context.getString(R.string.open), getPendingIntent(thread.getId(), thread
                        .getSubject().getUrl()))
                .addAction(R.drawable.ic_eye_off, context.getString(R.string.mark_as_read), getReadOnlyPendingIntent(thread.getId(), thread
                        .getSubject().getUrl()))
                .build();
        showNotification((int) thread.getId(), toAdd);
    }

    private android.app.Notification getNotificationWithComment(Context context, Bitmap largeIcon, int accentColor,
                                                                Notification thread, Comment comment) {
        return getNotification(thread.getSubject().getTitle(),
                thread.getRepository().getFullName())
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(comment.getBody())
                        .setBigContentTitle(comment.getUser() != null ? comment.getUser().getLogin() : ""))
                .setLargeIcon(largeIcon)
                .setContentIntent(getPendingIntent(thread.getId(), thread.getSubject().getUrl()))
                .setGroup(NOTIFICATION_GROUP_ID)
                .setColor(accentColor)
                .addAction(R.drawable.ic_github, context.getString(R.string.open), getPendingIntent(thread.getId(),
                        thread.getSubject().getUrl()))
                .addAction(R.drawable.ic_eye_off, context.getString(R.string.mark_as_read), getReadOnlyPendingIntent(thread.getId(),
                        thread.getSubject().getUrl()))
                .build();
    }

    private android.app.Notification getSummaryGroupNotification(@NonNull Notification notification, int accentColor, Bitmap largeIcon) {
        return getNotification(notification.getSubject().getTitle(), notification.getRepository().getFullName())
                .setLargeIcon(largeIcon)
                .setGroup(NOTIFICATION_GROUP_ID)
                .setGroupSummary(true)
                .setColor(accentColor)
                .setContentIntent(getPendingIntent(notification.getId(), notification.getSubject().getUrl()))
                .addAction(R.drawable.ic_github, getString(R.string.open),
                        getPendingIntent(notification.getId(), notification.getSubject().getUrl()))
                .addAction(R.drawable.ic_eye_off, getString(R.string.mark_as_read), getReadOnlyPendingIntent(notification.getId(),
                        notification.getSubject().getUrl()))
                .setAutoCancel(true)
                .build();
    }

    private NotificationCompat.Builder getNotification(@NonNull String title, @NonNull String message) {
        return new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message);
    }

    private void showNotification(int id, android.app.Notification notification) {
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(id, notification);
    }

    private PendingIntent getReadOnlyPendingIntent(long id, @NonNull String url) {
        Intent intent = ReadNotificationService.start(this, id, url, true);
        return PendingIntent.getService(this, (int) (id / 2), intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent getPendingIntent(long id, @NonNull String url) {
        Intent intent = ReadNotificationService.start(this, id, url);
        return PendingIntent.getService(this, (int) id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
