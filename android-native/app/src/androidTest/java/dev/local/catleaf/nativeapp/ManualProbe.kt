package dev.local.catleaf.nativeapp

/**
 * 需要人工分两次运行、不能进默认测试套件的检查（例如跨进程持久化探针）。
 * 默认套件通过 testInstrumentationRunnerArguments["notAnnotation"] 跳过带本注解的用例；
 * 显式 `am instrument -e class <类#方法>` 时不会被跳过。
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class ManualProbe
