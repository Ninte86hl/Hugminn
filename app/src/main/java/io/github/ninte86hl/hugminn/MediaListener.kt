package io.github.ninte86hl.hugminn

import android.service.notification.NotificationListenerService

/**
 * Only there so Android lets Hugminn see the media sessions (song, artist, cover, position) for the
 * music tile. Hugminn switches it on itself through the root service. It does not read notifications.
 */
class MediaListener : NotificationListenerService()
