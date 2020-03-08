package com.github.adamantcheese.chan.core.di;

import com.github.adamantcheese.database.di.DatabaseComponent;
import com.github.adamantcheese.database.source.YoutubeLinkExtraContentLocalSource;

import org.codejargon.feather.Provides;

import javax.inject.Singleton;

public class RoomDatabaseModule {
    private DatabaseComponent databaseComponent;

    public RoomDatabaseModule(DatabaseComponent databaseComponent) {
        this.databaseComponent = databaseComponent;
    }

    @Provides
    @Singleton
    public YoutubeLinkExtraContentLocalSource provideYoutubeLinkExtraContentLocalSource() {
        return new YoutubeLinkExtraContentLocalSource(databaseComponent.getKurobaDatabase());
    }
}