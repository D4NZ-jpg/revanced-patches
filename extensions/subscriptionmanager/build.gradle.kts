dependencies {
    compileOnly(project(":extensions:subscriptionmanager:stub"))
    testImplementation(project(":extensions:subscriptionmanager:stub"))
    testImplementation(libs.junit)
}

extension {
    name = "extensions/subscriptionmanager.rve"
}

android {
    namespace = "app.revanced.extension.d4nz"

    defaultConfig {
        minSdk = 26
    }
}
