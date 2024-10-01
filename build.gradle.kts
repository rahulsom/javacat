import nebula.plugin.contacts.Contact
import nebula.plugin.contacts.ContactsExtension

buildscript {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    alias(libs.plugins.waenaRoot)
    alias(libs.plugins.waenaPublished).apply(false)
    alias(libs.plugins.dgs).apply(false)
    alias(libs.plugins.download).apply(false)
}

allprojects {
    group = "io.github.pulpogato"

    extensions.findByType<ContactsExtension>()?.apply {
        addPerson("rahulsom@noreply.github.com", delegateClosureOf<Contact> {
            moniker("Rahul Somasunderam")
            roles("owner")
            github("https://github.com/rahulsom")
        })
    }
}

subprojects {
    repositories {
        mavenCentral()
    }
}

waena {
    useCentralPortal.set(true)
}