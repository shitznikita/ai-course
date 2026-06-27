pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "ai-course"

include("day-01-llm-rest-kotlin")
include("day-02-response-format-kotlin")
include("day-03-reasoning-methods-kotlin")
include("day-04-temperature-kotlin")
include("day-05-model-versions-kotlin")
include("day-06-first-agent-kotlin")
include("day-07-persistent-context-kotlin")
include("day-08-token-accounting-kotlin")
include("day-09-history-compression-kotlin")
include("day-10-context-strategies-kotlin")
include("day-11-memory-layers-kotlin")
include("day-12-personalization-kotlin")
include("day-13-task-state-machine-kotlin")
include("day-14-state-invariants-kotlin")
include("day-15-controlled-transitions-kotlin")
include("day-16-mcp-connection-kotlin")
include("day-17-telegram-mcp-tool-kotlin")
include("day-18-telegram-course-scheduler-kotlin")
include("day-19-mcp-tool-composition-kotlin")
