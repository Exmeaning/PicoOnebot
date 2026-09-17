package momoi.plugin.apkmixin

import momoi.plugin.apkmixin.utils.ZipUtil
import pxb.android.axml.Axml
import pxb.android.axml.AxmlReader
import pxb.android.axml.AxmlWriter
import pxb.android.axml.NodeVisitor
import java.io.File
import java.util.zip.ZipFile

/** Adds manifest entries that ManifestEditor cannot express through its CLI. */
internal object ManifestPatcher {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val ATTR_LABEL = 0x01010001
    private const val ATTR_NAME = 0x01010003
    private const val ATTR_PERMISSION = 0x01010006
    private const val ATTR_EXPORTED = 0x01010010

    fun patch(
        apk: File,
        usesPermissions: Set<String>,
        accessibilityServiceClass: String,
        accessibilityServiceLabel: String
    ) {
        if (usesPermissions.isEmpty() && accessibilityServiceClass.isBlank()) return

        val manifestBytes = ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
                ?: error("AndroidManifest.xml is missing from ${apk.name}")
            zip.getInputStream(entry).use { it.readBytes() }
        }
        val document = Axml()
        AxmlReader(manifestBytes).accept(document)
        val manifest = document.firsts.firstOrNull { it.name == "manifest" }
            ?: error("manifest root is missing from ${apk.name}")
        val application = manifest.children.firstOrNull { it.name == "application" }
            ?: error("application node is missing from ${apk.name}")

        for (permission in usesPermissions) {
            if (manifest.children.any { it.name == "uses-permission" && it.androidName() == permission }) continue
            val node = manifest.child(null, "uses-permission") as Axml.Node
            node.attr(ANDROID_NS, "name", ATTR_NAME, NodeVisitor.TYPE_STRING, permission)
            manifest.children.remove(node)
            manifest.children.add(manifest.children.indexOf(application), node)
        }

        if (accessibilityServiceClass.isNotBlank() &&
            application.children.none { it.name == "service" && it.androidName() == accessibilityServiceClass }
        ) {
            val service = application.child(null, "service") as Axml.Node
            service.attr(ANDROID_NS, "name", ATTR_NAME, NodeVisitor.TYPE_STRING, accessibilityServiceClass)
            service.attr(
                ANDROID_NS,
                "permission",
                ATTR_PERMISSION,
                NodeVisitor.TYPE_STRING,
                "android.permission.BIND_ACCESSIBILITY_SERVICE"
            )
            // AccessibilityManager is a system process, so it must be able to bind to the service.
            // BIND_ACCESSIBILITY_SERVICE is signature-protected and remains the access boundary.
            service.attr(ANDROID_NS, "exported", ATTR_EXPORTED, NodeVisitor.TYPE_INT_BOOLEAN, -1)
            if (accessibilityServiceLabel.isNotBlank()) {
                service.attr(
                    ANDROID_NS,
                    "label",
                    ATTR_LABEL,
                    NodeVisitor.TYPE_STRING,
                    accessibilityServiceLabel
                )
            }
            val filter = service.child(null, "intent-filter")
            val action = filter.child(null, "action")
            action.attr(
                ANDROID_NS,
                "name",
                ATTR_NAME,
                NodeVisitor.TYPE_STRING,
                "android.accessibilityservice.AccessibilityService"
            )
        }

        val writer = AxmlWriter()
        document.accept(writer)
        val replacement = File.createTempFile("pico-manifest", ".xml", apk.parentFile)
        try {
            replacement.writeBytes(writer.toByteArray())
            ZipUtil.addOrReplaceFilesInZip(apk, mapOf("AndroidManifest.xml" to replacement))
        } finally {
            replacement.delete()
        }
    }

    private fun Axml.Node.androidName(): String? =
        attrs.firstOrNull { it.ns == ANDROID_NS && it.name == "name" }?.value as? String
}
