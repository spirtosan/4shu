#include <jni.h>

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_fshu_util_CryptoHelper_getPepper(JNIEnv* env, jobject) {
    static const jbyte pepper[32] = {
        (jbyte)0xA3, (jbyte)0x7F, (jbyte)0x2C, (jbyte)0xE1,
        (jbyte)0x58, (jbyte)0x94, (jbyte)0xB0, (jbyte)0x3D,
        (jbyte)0x6E, (jbyte)0xF2, (jbyte)0x11, (jbyte)0xC7,
        (jbyte)0x4A, (jbyte)0x85, (jbyte)0x39, (jbyte)0xDE,
        (jbyte)0x70, (jbyte)0xBC, (jbyte)0x5F, (jbyte)0x08,
        (jbyte)0xD4, (jbyte)0x96, (jbyte)0x23, (jbyte)0xAB,
        (jbyte)0xE4, (jbyte)0x17, (jbyte)0x6D, (jbyte)0xF9,
        (jbyte)0xC0, (jbyte)0x4B, (jbyte)0x8E, (jbyte)0x52
    };
    jbyteArray result = env->NewByteArray(32);
    env->SetByteArrayRegion(result, 0, 32, pepper);
    return result;
}
