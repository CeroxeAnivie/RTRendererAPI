#pragma once

#include <jni.h>

#include "streamline_types.hpp"

namespace rtrenderer::nvidia {

StreamlineFrame readStreamlineFrame(
        JNIEnv* environment,
        jobject resources,
        jobject constants,
        jlong sequence
);

StreamlineFrame readStreamlineFrameGeneration(
        JNIEnv* environment,
        jobject hudless,
        jobject depth,
        jobject motion,
        jobject constants,
        jlong sequence
);

}
