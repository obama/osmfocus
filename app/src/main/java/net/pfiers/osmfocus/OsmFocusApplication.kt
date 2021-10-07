package net.pfiers.osmfocus

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import net.pfiers.osmfocus.service.basemaps.BaseMapRepository
import net.pfiers.osmfocus.service.db.Db
import net.pfiers.osmfocus.service.db.TagInfoRepository
import net.pfiers.osmfocus.service.settings.SettingsSerializer
import net.pfiers.osmfocus.service.taginfo.TagInfoApiConfig
import java.net.URI


class OsmFocusApplication : Application() {
    val db by lazy { Db.getDatabase(this) }
    val baseMapRepository by lazy { BaseMapRepository(db.baseMapDefinitionDao()) }
    val wikiPageRepository by lazy { TagInfoRepository(db.wikiPageDao(), TagInfoApiConfig(URI("https://taginfo.openstreetmap.org"), "fdsfd")) }

    val settingsDataStore: DataStore<Settings> by dataStore (
        fileName = "settings.pb",
        serializer = SettingsSerializer()
    )
}
