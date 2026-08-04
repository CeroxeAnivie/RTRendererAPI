#pragma once

#include <jni.h>

#include "nrd_session.hpp"

namespace rtrenderer::nvidia {

/** Reads and adversarially validates one Java NRD constants object at the JNI boundary. */
NrdFrameConstants readNrdFrameConstants(JNIEnv* environment, jobject constants);

}
