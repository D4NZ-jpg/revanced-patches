import java.security.MessageDigest

group = "app.revanced.patches.d4nz"

patches {
    about {
        name = "D4NZ ReVanced Patches"
        description = "Custom-only ReVanced patches"
        source = "https://github.com/D4NZ-jpg/revanced-patches"
        author = "D4NZ"
        contact = "https://github.com/D4NZ-jpg"
        website = "https://github.com/D4NZ-jpg/revanced-patches"
        license = "GNU General Public License v3.0"
    }
}

val officialPatches = rootProject.file("vendor/revanced-patches-6.1.1-dev.4.rvp")
val officialPatchesSha256 = "c34d809988c1059220dd9b1ace35f6b3872a0d0fa6ac3cdfa79858d0d152b6d6"

val verifyOfficialPatches by tasks.registering {
    inputs.file(officialPatches)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(officialPatches.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == officialPatchesSha256) {
            "Unexpected checksum for ${officialPatches.name}: $digest"
        }
    }
}

tasks.named("compileKotlin") {
    dependsOn(verifyOfficialPatches)
}

dependencies {
    compileOnly(files(officialPatches))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexplicit-backing-fields",
            "-Xcontext-parameters",
        )
    }
}
