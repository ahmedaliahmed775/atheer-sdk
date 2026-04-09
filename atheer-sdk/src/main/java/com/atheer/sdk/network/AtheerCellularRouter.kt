@file:Suppress("unused")
package com.atheer.sdk.network

/**
 * تم دمج وظائف هذا الكلاس في [AtheerNetworkRouter] عبر Feature Toggle (NetworkMode.PRIVATE_APN).
 * هذا الملف محتفظ به فقط لتجنب كسر مراجع خارجية.
 *
 * @see AtheerNetworkRouter.NetworkMode.PRIVATE_APN
 */
@Deprecated(
    "تم دمج وظائف APN في AtheerNetworkRouter. استخدم NetworkMode.PRIVATE_APN بدلاً من ذلك.",
    level = DeprecationLevel.WARNING
)
internal class AtheerCellularRouter
