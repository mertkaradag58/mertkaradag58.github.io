// Kotlin <-> C++ köprüsü.
// Kotlin tarafı: com.mertkaradag.stepviewer.StepNative.loadStep(path, deflection)
#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "step_mesher.h"

#define LOG_TAG "StepViewer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

void ThrowIOException(JNIEnv* env, const std::string& message) {
  jclass cls = env->FindClass("java/io/IOException");
  if (cls != nullptr) {
    env->ThrowNew(cls, message.c_str());
  }
}

template <typename T>
void ReleaseVector(std::vector<T>& v) {
  std::vector<T>().swap(v);
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_com_mertkaradag_stepviewer_StepNative_loadStep(JNIEnv* env, jobject /*thiz*/,
                                                    jstring jpath,
                                                    jdouble relativeDeflection) {
  const char* cpath = env->GetStringUTFChars(jpath, nullptr);
  const std::string path(cpath);
  env->ReleaseStringUTFChars(jpath, cpath);

  stepviewer::MeshOptions options;
  if (relativeDeflection > 0.0) {
    options.relativeDeflection = relativeDeflection;
  }

  stepviewer::MeshData mesh;
  std::string error;
  if (!stepviewer::LoadStepFile(path, options, mesh, error)) {
    ThrowIOException(env, error);
    return nullptr;
  }
  LOGI("Yuklendi: %d parca, %zu ucgen, %zu kose", mesh.partCount, mesh.triangleCount(),
       mesh.vertexCount());

  // Büyük dizileri Java'ya kopyaladıktan hemen sonra C++ tarafında serbest bırak,
  // böylece bellekte aynı anda iki kopya durmaz.
  jfloatArray jverts = env->NewFloatArray(static_cast<jsize>(mesh.vertices.size()));
  if (jverts == nullptr) return nullptr;  // OutOfMemoryError zaten fırlatıldı
  env->SetFloatArrayRegion(jverts, 0, static_cast<jsize>(mesh.vertices.size()),
                           mesh.vertices.data());
  ReleaseVector(mesh.vertices);

  jintArray jidx = env->NewIntArray(static_cast<jsize>(mesh.indices.size()));
  if (jidx == nullptr) return nullptr;
  env->SetIntArrayRegion(jidx, 0, static_cast<jsize>(mesh.indices.size()),
                         reinterpret_cast<const jint*>(mesh.indices.data()));
  ReleaseVector(mesh.indices);

  jfloatArray jedges = env->NewFloatArray(static_cast<jsize>(mesh.edges.size()));
  if (jedges == nullptr) return nullptr;
  env->SetFloatArrayRegion(jedges, 0, static_cast<jsize>(mesh.edges.size()), mesh.edges.data());
  ReleaseVector(mesh.edges);

  const float bbox[6] = {mesh.bboxMin[0], mesh.bboxMin[1], mesh.bboxMin[2],
                         mesh.bboxMax[0], mesh.bboxMax[1], mesh.bboxMax[2]};
  jfloatArray jbbox = env->NewFloatArray(6);
  if (jbbox == nullptr) return nullptr;
  env->SetFloatArrayRegion(jbbox, 0, 6, bbox);

  jclass meshClass = env->FindClass("com/mertkaradag/stepviewer/StepMesh");
  if (meshClass == nullptr) return nullptr;
  jmethodID ctor = env->GetMethodID(meshClass, "<init>", "([F[I[F[FII)V");
  if (ctor == nullptr) return nullptr;
  return env->NewObject(meshClass, ctor, jverts, jidx, jedges, jbbox,
                        static_cast<jint>(mesh.partCount), static_cast<jint>(mesh.faceCount));
}
