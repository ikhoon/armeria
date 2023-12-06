dependencies {
    implementation(project(":kubernetes"))
    implementation(libs.picocli)
    testImplementation(libs.kubernetes.junit.jupiter) {
        exclude(group = "io.fabric8", module="kubernetes-httpclient-okhttp")
    }
    testImplementation(libs.logback13)
}
