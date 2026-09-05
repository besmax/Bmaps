package bes.max.bmaps

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform