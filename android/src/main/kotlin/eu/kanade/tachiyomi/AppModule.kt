package eu.kanade.tachiyomi

import android.app.Application
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory

class AppModule(
    val app: Application,
) : InjektModule {
    override fun InjektRegistrar.registerInjectables() {
        addSingleton(app)

        addSingletonFactory { NetworkHelper(app) }
        
        addSingletonFactory { Json { ignoreUnknownKeys = true } }
    }
}
