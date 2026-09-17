package momoi.plugin.apkmixin.utils

import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import momoi.plugin.apkmixin.MutableDexFile

fun DexFile.findClass(type: String): ClassDef? =
    classes.find { it.type == type }