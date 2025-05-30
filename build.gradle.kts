// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    id("androidx.room") version "2.7.1" apply false
    id("com.google.protobuf") version "0.9.4" apply false
}