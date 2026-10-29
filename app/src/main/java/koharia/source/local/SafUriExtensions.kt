package koharia.source.local

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

internal fun Uri.isSafTreeUri(): Boolean =
    scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(this)
