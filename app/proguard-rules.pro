# Library consumer rules cover Hilt, Room, Media3, and kotlinx.serialization.
# Add narrowly scoped rules only when a release-build verification requires them.
# Preserve the generated release mapping file with its matching release artifact.

# WorkManager 2.8.1's Room 2.5 runtime instantiates this generated class with
# Class.newInstance(). Its consumer rule preserves the class name, but release
# verification found that R8 removed the required public no-argument constructor.
-keepclassmembers class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}
