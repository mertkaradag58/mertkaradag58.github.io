// Fotoğraftan model köprüsü. Kotlin tarafı: com.mertkaradag.stepviewer.photocad.PhotoCadNative
#include <jni.h>

#include <string>
#include <vector>

#include "photo_cad.h"

namespace {

bool ReadSketch(JNIEnv* env, jdoubleArray arr, stepviewer::Sketch& out) {
  if (arr == nullptr) return false;
  const jsize n = env->GetArrayLength(arr);
  std::vector<double> data(static_cast<size_t>(n));
  env->GetDoubleArrayRegion(arr, 0, n, data.data());
  return stepviewer::DecodeSketch(data.data(), data.size(), out);
}

std::string ToString(JNIEnv* env, jstring s) {
  if (s == nullptr) return {};
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string r(c != nullptr ? c : "");
  if (c != nullptr) env->ReleaseStringUTFChars(s, c);
  return r;
}

void ThrowIo(JNIEnv* env, const std::string& msg) {
  jclass cls = env->FindClass("java/io/IOException");
  if (cls != nullptr) env->ThrowNew(cls, msg.c_str());
}

}  // namespace

// Dönüş: [hacim mm³, en, derinlik, yükseklik]; hata durumunda IOException(kod).
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_mertkaradag_stepviewer_photocad_PhotoCadNative_buildSolid(
    JNIEnv* env, jobject /*thiz*/, jint mode, jdoubleArray sketchA, jdoubleArray sketchB,
    jdouble param, jint axis, jstring outPath, jstring name) {
  stepviewer::Sketch a, b;
  if (!ReadSketch(env, sketchA, a)) {
    ThrowIo(env, "open_profile");
    return nullptr;
  }
  if (mode == static_cast<jint>(stepviewer::PhotoCadMode::TwoView) && !ReadSketch(env, sketchB, b)) {
    ThrowIo(env, "open_profile");
    return nullptr;
  }
  const stepviewer::PhotoCadResult r = stepviewer::BuildPhotoSolid(
      static_cast<stepviewer::PhotoCadMode>(mode), a, b, param, axis, ToString(env, outPath),
      ToString(env, name));
  if (!r.ok) {
    ThrowIo(env, r.error.empty() ? "build_failed" : r.error);
    return nullptr;
  }
  const double out[4] = {r.volume, r.size[0], r.size[1], r.size[2]};
  jdoubleArray arr = env->NewDoubleArray(4);
  if (arr != nullptr) env->SetDoubleArrayRegion(arr, 0, 4, out);
  return arr;
}
