package momoi.plugin.apkmixin

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import java.io.File

class MutableDexFile() : DexFile {
    constructor(src: DexFile) : this() {
        classes.addAll(src.classes)
    }

    private val classes = mutableSetOf<ClassDef>()

    override fun getClasses(): MutableSet<ClassDef> {
        return classes
    }

    override fun getOpcodes() = Opcodes.getDefault()

}