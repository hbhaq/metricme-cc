plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization); application }
kotlin { jvmToolchain(17) }
application { mainClass.set("com.hbhaq.metricme.backend.ServerKt"); applicationDefaultJvmArgs = listOf("-Xmx256m", "-Djava.awt.headless=true") }
dependencies {
    implementation(project(":contracts"))
    implementation(platform("io.ktor:ktor-bom:${libs.versions.ktor.get()}"))
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-auth-jwt")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.zaxxer:HikariCP:6.3.3")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation(libs.junit)
}
