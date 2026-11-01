// Kotlin <-> C++ köprüsü.
// Kotlin tarafı: com.mertkaradag.stepviewer.StepNative
#include <android/log.h>
#include <jni.h>

#include <mutex>
#include <string>
#include <vector>

#include "step_mesher.h"

#define LOG_TAG "StepViewer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

// Son yüklenen modelin gerçek geometrileri (ölçüm için). İndeks = kimlik.
struct ModelStore {
  std::mutex mutex;
  std::vector<TopoDS_Edge> edges;
  std::vector<TopoDS_Face> faces;
  std::vector<TopoDS_Shape> parts;

  void Clear() {
    std::lock_guard<std::mutex> lock(mutex);
    std::vector<TopoDS_Edge>().swap(edges);
    std::vector<TopoDS_Face>().swap(faces);
    std::vector<TopoDS_Shape>().swap(parts);
  }
};
ModelStore gStore;

constexpr jint kKindEdge = 0;
constexpr jint kKindFace = 1;

bool GetStoredShape(jint kind, jint id, TopoDS_Shape& shape) {
  std::lock_guard<std::mutex> lock(gStore.mutex);
  if (id < 0) return false;
  const size_t i = static_cast<size_t>(id);
  if (kind == kKindEdge && i < gStore.edges.size()) {
    shape = gStore.edges[i];
    return true;
  }
  if (kind == kKindFace && i < gStore.faces.size()) {
    shape = gStore.faces[i];
    return true;
  }
  return false;
}

jdoubleArray ToJava(JNIEnv* env, const std::vector<double>& v) {
  jdoubleArray arr = env->NewDoubleArray(static_cast<jsize>(v.size()));
  if (arr != nullptr) env->SetDoubleArrayRegion(arr, 0, static_cast<jsize>(v.size()), v.data());
  return arr;
}

jintArray ToJava(JNIEnv* env, const std::vector<int32_t>& v) {
  jintArray arr = env->NewIntArray(static_cast<jsize>(v.size()));
  if (arr != nullptr) env->SetIntArrayRegion(arr, 0, static_cast<jsize>(v.size()), v.data());
  return arr;
}

jfloatArray ToJava(JNIEnv* env, const float* data, size_t n) {
  jfloatArray arr = env->NewFloatArray(static_cast<jsize>(n));
  if (arr != nullptr) env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(n), data);
  return arr;
}

void ThrowIOException(JNIEnv* env, const std::string& message) {
  jclass cls = env->FindClass("java/io/IOException");
  if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

template <typename T>
void ReleaseVector(std::vector<T>& v) {
  std::vector<T>().swap(v);
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_com_mertkaradag_stepviewer_StepNative_loadModel(JNIEnv* env, jobject /*thiz*/, jstring jpath,
                                                     jdouble relativeDeflection) {
  const char* cpath = env->GetStringUTFChars(jpath, nullptr);
  const std::string path(cpath);
  env->ReleaseStringUTFChars(jpath, cpath);

  stepviewer::MeshOptions options;
  if (relativeDeflection > 0.0) options.relativeDeflection = relativeDeflection;

  gStore.Clear();  // eski modelin belleğini bırak

  stepviewer::MeshData mesh;
  std::string error;
  if (!stepviewer::LoadModelFile(path, options, mesh, error)) {
    ThrowIOException(env, error);
    return nullptr;
  }
  LOGI("Yuklendi: %d parca, %d yuzey, %zu ucgen", mesh.partCount(), mesh.faceCount(),
       mesh.triangleCount());

  // Büyük dizileri Java'ya kopyaladıktan hemen sonra C++ tarafında serbest bırak.
  jfloatArray jverts = ToJava(env, mesh.vertices.data(), mesh.vertices.size());
  if (jverts == nullptr) return nullptr;  // OutOfMemoryError zaten fırlatıldı
  ReleaseVector(mesh.vertices);

  jintArray jidx = env->NewIntArray(static_cast<jsize>(mesh.indices.size()));
  if (jidx == nullptr) return nullptr;
  env->SetIntArrayRegion(jidx, 0, static_cast<jsize>(mesh.indices.size()),
                         reinterpret_cast<const jint*>(mesh.indices.data()));
  ReleaseVector(mesh.indices);

  jfloatArray jedges = ToJava(env, mesh.edges.data(), mesh.edges.size());
  if (jedges == nullptr) return nullptr;
  ReleaseVector(mesh.edges);

  jintArray jfaceOff = ToJava(env, mesh.faceOffsets);
  jintArray jedgeOff = ToJava(env, mesh.edgeOffsets);
  jintArray jpartFace = ToJava(env, mesh.partFaceOffsets);
  jintArray jpartEdge = ToJava(env, mesh.partEdgeOffsets);
  const float bbox[6] = {mesh.bboxMin[0], mesh.bboxMin[1], mesh.bboxMin[2],
                         mesh.bboxMax[0], mesh.bboxMax[1], mesh.bboxMax[2]};
  jfloatArray jbbox = ToJava(env, bbox, 6);

  // Ağaç: isimler tek bir UTF-8 bayt dizisinde '\n' ile ayrılmış olarak gider
  // (JNI'nin "modified UTF-8" kısıtlarına takılmamak için).
  std::string names;
  std::vector<int32_t> depth, leafStart, leafEnd, isAsm;
  for (const auto& n : mesh.tree) {
    std::string name = n.name;
    for (char& c : name) {
      if (c == '\n' || c == '\r') c = ' ';
    }
    names += name;
    names += '\n';
    depth.push_back(n.depth);
    leafStart.push_back(n.leafStart);
    leafEnd.push_back(n.leafEnd);
    isAsm.push_back(n.isAssembly ? 1 : 0);
  }
  jbyteArray jnames = env->NewByteArray(static_cast<jsize>(names.size()));
  if (jnames == nullptr) return nullptr;
  env->SetByteArrayRegion(jnames, 0, static_cast<jsize>(names.size()),
                          reinterpret_cast<const jbyte*>(names.data()));
  jintArray jdepth = ToJava(env, depth);
  jintArray jleafStart = ToJava(env, leafStart);
  jintArray jleafEnd = ToJava(env, leafEnd);
  jintArray jisAsm = ToJava(env, isAsm);
  if (!jfaceOff || !jedgeOff || !jpartFace || !jpartEdge || !jbbox || !jdepth || !jleafStart ||
      !jleafEnd || !jisAsm) {
    return nullptr;
  }

  jclass meshClass = env->FindClass("com/mertkaradag/stepviewer/StepMesh");
  if (meshClass == nullptr) return nullptr;
  jmethodID ctor =
      env->GetMethodID(meshClass, "<init>", "([F[I[F[I[I[I[I[F[B[I[I[I[II)V");
  if (ctor == nullptr) return nullptr;
  jobject result = env->NewObject(meshClass, ctor, jverts, jidx, jedges, jfaceOff, jedgeOff,
                                  jpartFace, jpartEdge, jbbox, jnames, jdepth, jleafStart,
                                  jleafEnd, jisAsm, static_cast<jint>(mesh.format));
  if (result != nullptr) {
    std::lock_guard<std::mutex> lock(gStore.mutex);
    gStore.edges = std::move(mesh.edgeShapes);
    gStore.faces = std::move(mesh.faceShapes);
    gStore.parts = std::move(mesh.partShapes);
  }
  return result;
}

// Dönüş: [tür, uzunluk, alan, yarıçap, yarıçap2, açı, cx, cy, cz, dx, dy, dz] veya null
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_mertkaradag_stepviewer_StepNative_shapeInfo(JNIEnv* env, jobject /*thiz*/, jint kind,
                                                     jint id) {
  TopoDS_Shape shape;
  stepviewer::ShapeInfo i;
  if (!GetStoredShape(kind, id, shape) || !stepviewer::GetShapeInfo(shape, i)) return nullptr;
  return ToJava(env, std::vector<double>{static_cast<double>(i.kind), i.length, i.area, i.radius, i.radius2,
                      i.angleDeg, i.center[0], i.center[1], i.center[2], i.dir[0], i.dir[1],
                      i.dir[2]});
}

// Dönüş: [mesafe, p1x, p1y, p1z, p2x, p2y, p2z, açı, merkez/eksen mesafesi, paralel düzlem
// mesafesi] (yoksa -1) veya null
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_mertkaradag_stepviewer_StepNative_measurePair(JNIEnv* env, jobject /*thiz*/,
                                                       jint kindA, jint idA, jint kindB,
                                                       jint idB) {
  TopoDS_Shape a, b;
  stepviewer::PairMeasure m;
  if (!GetStoredShape(kindA, idA, a) || !GetStoredShape(kindB, idB, b) ||
      !stepviewer::MeasurePair(a, b, m)) {
    return nullptr;
  }
  return ToJava(env, std::vector<double>{m.distance, m.p1[0], m.p1[1], m.p1[2], m.p2[0], m.p2[1], m.p2[2],
                      m.angleDeg, m.centerDistance, m.parallelDistance});
}

// partIds null ise tüm model. Dönüş: [hacim, alan, cx, cy, cz, katı var mı (1/0)] veya null
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_mertkaradag_stepviewer_StepNative_massProps(JNIEnv* env, jobject /*thiz*/,
                                                     jintArray jpartIds) {
  std::vector<TopoDS_Shape> shapes;
  {
    std::lock_guard<std::mutex> lock(gStore.mutex);
    if (jpartIds == nullptr) {
      shapes = gStore.parts;
    } else {
      const jsize n = env->GetArrayLength(jpartIds);
      std::vector<jint> ids(static_cast<size_t>(n));
      env->GetIntArrayRegion(jpartIds, 0, n, ids.data());
      for (jint id : ids) {
        if (id >= 0 && static_cast<size_t>(id) < gStore.parts.size()) {
          shapes.push_back(gStore.parts[static_cast<size_t>(id)]);
        }
      }
    }
  }
  stepviewer::MassProps p;
  if (shapes.empty() || !stepviewer::ComputeMassProps(shapes, p)) return nullptr;
  return ToJava(env, std::vector<double>{p.volume, p.area, p.center[0], p.center[1], p.center[2],
                      p.hasSolids ? 1.0 : 0.0});
}
