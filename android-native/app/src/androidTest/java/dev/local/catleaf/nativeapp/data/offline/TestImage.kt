package dev.local.catleaf.nativeapp.data.offline

import android.util.Base64

internal object TestImage {
    // 2x2 red PNG; generated fixture, decodable by Android BitmapFactory.
    val png: ByteArray = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEElEQVR4nGP4z8AARAwQCgAf7gP9i18U1AAAAABJRU5ErkJggg==",
        Base64.DEFAULT,
    )
}
