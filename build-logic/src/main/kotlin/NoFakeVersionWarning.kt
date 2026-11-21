import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * goodwy-commons shows a "fake version of the app" dialog on screen opens for any package outside its own
 * namespaces, which includes this app (com.mhw.phone). This empties the methods that show it, so the
 * dialog never appears, in every build type. If a commons update renames either method the build fails,
 * so the dialog cannot silently come back.
 */
abstract class NoFakeVersionWarning : AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun isInstrumentable(classData: ClassData) = classData.className in TARGETS

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor {
        val className = classContext.currentClassData.className
        val target = TARGETS.getValue(className)
        return object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            private var found = false

            override fun visitMethod(
                access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?
            ): MethodVisitor? {
                val next = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name != target.first || descriptor != target.second) return next
                found = true
                // Body becomes a bare `return`; the original body is dropped (returning null)
                next.visitCode()
                next.visitInsn(Opcodes.RETURN)
                next.visitMaxs(0, Type.getArgumentsAndReturnSizes(descriptor) shr 2)
                next.visitEnd()
                return null
            }

            override fun visitEnd() {
                check(found) { "NoFakeVersionWarning: ${target.first}${target.second} not found in $className" }
                super.visitEnd()
            }
        }
    }

    private companion object {
        // class -> (static void method, descriptor)
        val TARGETS = mapOf(
            "com.goodwy.commons.extensions.ActivityKt" to
                ("showModdedAppWarning" to "(Lcom/goodwy/commons/activities/BaseSimpleActivity;)V"),
            "com.goodwy.commons.compose.extensions.ComposeActivityExtensionsKt" to
                ("FakeVersionCheck" to "(Landroidx/compose/runtime/Composer;I)V"),
            // The gate the Compose screens share (it calls back to show the dialog)
            "com.goodwy.commons.compose.extensions.ActivityExtensionsKt" to
                ("fakeVersionCheck" to "(Landroid/content/Context;Lkotlin/jvm/functions/Function0;)V")
        )
    }
}

/** Applies [NoFakeVersionWarning] to every variant of the app. */
class NoFakeVersionWarningPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(project: org.gradle.api.Project) {
        project.extensions.getByType(com.android.build.api.variant.ApplicationAndroidComponentsExtension::class.java)
            .onVariants { variant ->
                variant.instrumentation.transformClassesWith(
                    NoFakeVersionWarning::class.java,
                    com.android.build.api.instrumentation.InstrumentationScope.ALL
                ) {}
            }
    }
}
