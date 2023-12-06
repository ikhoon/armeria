dependencies {
    api(libs.kubernetes.client.api)
    testImplementation(variantOf(libs.kubernetes.client.api) { classifier("tests") })
    testImplementation(libs.kubernetes.server.mock)
    testImplementation(libs.logback13)
}
