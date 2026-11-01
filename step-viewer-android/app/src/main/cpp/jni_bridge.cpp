// Kotlin <-> C++ köprüsü.
// Kotlin tarafı: com.mertkaradag.stepviewer.StepNative.loadStep(path, deflection)
#include <jni.h>
#include <android/log.h>

#include <mutex>
#include <string>
#include <vector>

#include "step_mesher.h"

#define LOG_TAG "StepViewer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

// Son yüklenen modelin gerçek kenar geometrileri (ölçüm için). İndeks = kenar kimliği.
std::mutex gEdgesMutex;
std::vector<TopoDS_Edge> gEdges;

bool GetStoredEdge(jint id, TopoDS_Edge& edge) {
  std::lock_guard<std::mutex> lock(gEdgesMutex);
  if (id < 0 || static_cast<size_t>(id) >= gEdges.size()) return false;
  edge = gEdges[static_cast<size_t>(id)];
  return true;
}

jdoubleArray ToJava(JNIEnv* env, const std::vector<double>& v) {
  jdoubleArray arr = env->NewDoubleArray(static_cast<jsize>(v.size()));
  if (arr != nullptr) {
    env->SetDoubleArrayRegion(arr, 0, static_cast<jsize>(v.size()), v.data());
  }
  return arr;
}

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

  {
    // Yeni model yükleniyor: eski kenarları bırak (bellek).
    std::lock_guard<std::mutex> lock(gEdgesMutex);
    std::vector<TopoDS_Edge>().swap(gEdges);
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

  jintArray joffsets = env->NewIntArray(static_cast<jsize>(mesh.edgeOffsets.size()));
  if (joffsets == nullptr) return nullptr;
  env->SetIntArrayRegion(joffsets, 0, static_cast<jsize>(mesh.edgeOffsets.size()),
                         mesh.edgeOffsets.data());
  ReleaseVector(mesh.edgeOffsets);

  const float bbox[6] = {mesh.bboxMin[0], mesh.bboxMin[1], mesh.bboxMin[2],
                         mesh.bboxMax[0], mesh.bboxMax[1], mesh.bboxMax[2]};
  jfloatArray jbbox = env->NewFloatArray(6);
  if (jbbox == nullptr) return nullptr;
  env->SetFloatArrayRegion(jbbox, 0, 6, bbox);

  jclass meshClass = env->FindClass("com/mertkaradag/stepviewer/StepMesh");
  if (meshClass == nullptr) return nullptr;
  jmethodID ctor = env->GetMethodID(meshClass, "<init>", "([F[I[F[I[FII)V");
  if (ctor == nullptr) return nullptr;
  jobject result = env->NewObject(meshClass, ctor, jverts, jidx, jedges, joffsets, jbbox,
                                  static_cast<jint>(mesh.partCount),
                                  static_cast<jint>(mesh.faceCount));
  if (result != nullptr) {
    std::lock_guard<std::mutex> lock(gEdgesMutex);
    gEdges = std::move(mesh.edgeShapes);
  }
  return result;
}

// Dönüş: [tip, uzunluk, yarıçap, merkezX, merkezY, merkezZ] veya null
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_mertkaradag_stepviewer_StepNative_edgeInfo(JNIEnv* env, jobject /*thiz*/, jint id) {
  TopoDS_Edge edge;
  stepviewer::EdgeInfo info;
  if (!GetStoredEdge(id, edge) || !stepviewer::GetEdgeInfo(edge, info)) return nullptr;
  return ToJava(env, {static_cast<double>(info.type), info.length, info.radius, info.center[0],
                      info.center[1], info.center[2]});
}

// Dönüş: [mesafe, p1x, p1y, p1z, p2x, p2y, p2z, açı (yoksa -1), merkez mesafesi (yoksa -1)]
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_mertkaradag_stepviewer_StepNative_measureEdges(JNIEnv* env, jobject /*thiz*/, jint a,
                                                        jint b) {
  TopoDS_Edge ea, eb;
  stepviewer::EdgeDistance d;
  if (!GetStoredEdge(a, ea) || !GetStoredEdge(b, eb) || !stepviewer::MeasureEdges(ea, eb, d)) {
    return nullptr;
  }
  return ToJava(env, {d.distance, d.p1[0], d.p1[1], d.p1[2], d.p2[0], d.p2[1], d.p2[2],
                      d.angleDeg, d.centerDistance});
}
