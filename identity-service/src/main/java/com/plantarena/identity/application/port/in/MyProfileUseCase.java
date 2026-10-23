package com.plantarena.identity.application.port.in;

import com.plantarena.identity.application.UserResult;
import com.plantarena.shared.security.CurrentActor;


public interface MyProfileUseCase {

    UserResult me(CurrentActor actor);

    UserResult updateLocation(CurrentActor actor, double latitude, double longitude);
}
