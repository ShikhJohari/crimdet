plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "com.crimdet"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

javafx {
    version = "21.0.2"
    modules("javafx.controls", "javafx.fxml", "javafx.swing", "javafx.media")
}

val atlantafxVersion = "2.0.1"
val ikonliVersion = "12.3.1"
val djlVersion = "0.31.1"
val javacvVersion = "1.5.11"
val h2Version = "2.3.232"
val jdbiVersion = "3.47.0"
val hikariVersion = "6.2.1"
val logbackVersion = "1.5.16"

dependencies {
    // AtlantaFX theme
    implementation("io.github.mkpaz:atlantafx-base:$atlantafxVersion")

    // Ikonli icons
    implementation("org.kordamp.ikonli:ikonli-javafx:$ikonliVersion")
    implementation("org.kordamp.ikonli:ikonli-materialdesign2-pack:$ikonliVersion")

    // DJL - Deep Java Library
    implementation("ai.djl:api:$djlVersion")
    implementation("ai.djl:basicdataset:$djlVersion")
    implementation("ai.djl:model-zoo:$djlVersion")
    implementation("ai.djl.pytorch:pytorch-engine:$djlVersion")

    // JavaCV - webcam access
    implementation("org.bytedeco:javacv-platform:$javacvVersion")

    // H2 embedded database
    implementation("com.h2database:h2:$h2Version")

    // JDBI database access
    implementation("org.jdbi:jdbi3-core:$jdbiVersion")
    implementation("org.jdbi:jdbi3-sqlobject:$jdbiVersion")

    // HikariCP connection pool
    implementation("com.zaxxer:HikariCP:$hikariVersion")

    // Logback logging
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    // JUnit 5 testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.crimdet.App")
}

tasks.test {
    useJUnitPlatform()
}

// --add-opens flags for JavaFX + AtlantaFX (non-modular setup)
val addOpensFlags = listOf(
    "--add-opens", "javafx.base/com.sun.javafx.event=ALL-UNNAMED",
    "--add-opens", "javafx.controls/com.sun.javafx.scene.control=ALL-UNNAMED",
    "--add-opens", "javafx.controls/com.sun.javafx.scene.control.behavior=ALL-UNNAMED",
    "--add-opens", "javafx.controls/com.sun.javafx.scene.control.inputmap=ALL-UNNAMED",
    "--add-opens", "javafx.controls/com.sun.javafx.scene.control.skin=ALL-UNNAMED",
    "--add-opens", "javafx.graphics/com.sun.javafx.scene=ALL-UNNAMED",
    "--add-opens", "javafx.graphics/com.sun.javafx.scene.traversal=ALL-UNNAMED",
    "--add-opens", "javafx.graphics/com.sun.javafx.css=ALL-UNNAMED",
    "--add-opens", "javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED",
    "--add-opens", "javafx.graphics/javafx.scene=ALL-UNNAMED",
    "--add-exports", "javafx.base/com.sun.javafx.event=ALL-UNNAMED",
    "--add-exports", "javafx.controls/com.sun.javafx.scene.control.behavior=ALL-UNNAMED",
    "--add-exports", "javafx.controls/com.sun.javafx.scene.control.inputmap=ALL-UNNAMED",
    "--add-exports", "javafx.controls/com.sun.javafx.scene.control=ALL-UNNAMED",
    // Java 22+: allow JavaCPP/OpenCV native library loading
    "--enable-native-access=ALL-UNNAMED",
)

tasks.withType<JavaExec> {
    jvmArgs(addOpensFlags)
}

tasks.withType<Test> {
    jvmArgs(addOpensFlags)
}
