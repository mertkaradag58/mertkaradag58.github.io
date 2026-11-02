// Kotlin <-> C++ köprüsü.
// Kotlin tarafı: com.mertkaradag.stepviewer.StepNative
#include <android/log.h>
#include <jni.h>

#include <Standard_Failure.hxx>
#include <TopoDS.hxx>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "drawing_views.h"
#include "dxf_export.h"
#include "hole_finder.h"
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
  std::vector<int32_t> partFaceOffsets;  // parça i -> yüzeyler
  // Mesh dosyalarında (OBJ, STL, ...) ölçüm bu geometriden yapılır; CAD'de boş.
  std::shared_ptr<stepviewer::MeshGeometry> mesh;
  // STEP PMI (ölçü, tolerans, referans)
  stepviewer::PmiData pmi;

  void Clear() {
    std::lock_guard<std::mutex> lock(mutex);
    pmi = stepviewer::PmiData();
    std::vector<TopoDS_Edge>().swap(edges);
    std::vector<TopoDS_Face>().swap(faces);
    std::vector<TopoDS_Shape>().swap(parts);
    std::vector<int32_t>().swap(partFaceOffsets);
    mesh.reset();
  }
};
ModelStore gStore;

std::shared_ptr<stepviewer::MeshGeometry> CurrentMesh() {
  std::lock_guard<std::mutex> lock(gStore.mutex);
  return gStore.mesh;
}

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
    gStore.partFaceOffsets = std::move(mesh.partFaceOffsets);
    gStore.pmi = std::move(mesh.pmi);
    gStore.mesh = std::move(mesh.meshGeometry);
  }
  return result;
}

// Dönüş: [tür, uzunluk, alan, yarıçap, yarıçap2, açı, cx, cy, cz, dx, dy, dz] veya null
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_mertkaradag_stepviewer_StepNative_shapeInfo(JNIEnv* env, jobject /*thiz*/, jint kind,
                                                     jint id) {
  TopoDS_Shape shape;
  stepviewer::ShapeInfo i;
  if (auto mesh = CurrentMesh()) {
    if (!stepviewer::MeshFeatureInfo(*mesh, kind, id, i)) return nullptr;
  } else if (!GetStoredShape(kind, id, shape) || !stepviewer::GetShapeInfo(shape, i)) {
    return nullptr;
  }
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
  if (auto mesh = CurrentMesh()) {
    if (!stepviewer::MeshMeasurePair(*mesh, kindA, idA, kindB, idB, m)) return nullptr;
  } else if (!GetStoredShape(kindA, idA, a) || !GetStoredShape(kindB, idB, b) ||
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
  if (auto mesh = CurrentMesh()) {
    std::vector<int> ids;
    if (jpartIds != nullptr) {
      const jsize n = env->GetArrayLength(jpartIds);
      std::vector<jint> raw(static_cast<size_t>(n));
      env->GetIntArrayRegion(jpartIds, 0, n, raw.data());
      ids.assign(raw.begin(), raw.end());
    }
    stepviewer::MassProps p;
    if (!stepviewer::MeshMassProps(*mesh, jpartIds != nullptr ? &ids : nullptr, p)) return nullptr;
    return ToJava(env, std::vector<double>{p.volume, p.area, p.center[0], p.center[1], p.center[2],
                                           p.hasSolids ? 1.0 : 0.0});
  }
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

// PMI: [float[] segmentler, int[] (tür, segBaş, segSon, konumVar)*n, float[] konumlar, byte[] metinler]
// Metinler UTF-8, '\n' ile ayrılmış. PMI yoksa null.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_mertkaradag_stepviewer_StepNative_pmiData(JNIEnv* env, jobject /*thiz*/) {
  stepviewer::PmiData pmi;
  {
    std::lock_guard<std::mutex> lock(gStore.mutex);
    if (gStore.pmi.items.empty()) return nullptr;
    pmi = gStore.pmi;
  }
  std::vector<int32_t> items;
  std::vector<float> anchors;
  std::string texts;
  for (const auto& it : pmi.items) {
    items.push_back(static_cast<int32_t>(it.kind));
    items.push_back(it.segStart);
    items.push_back(it.segEnd);
    items.push_back(it.hasAnchor ? 1 : 0);
    anchors.insert(anchors.end(), {it.anchor[0], it.anchor[1], it.anchor[2]});
    std::string t = it.text;
    for (char& c : t) {
      if (c == '\n' || c == '\r') c = ' ';
    }
    texts += t;
    texts += '\n';
  }
  jclass objClass = env->FindClass("java/lang/Object");
  if (objClass == nullptr) return nullptr;
  jobjectArray result = env->NewObjectArray(4, objClass, nullptr);
  if (result == nullptr) return nullptr;
  jfloatArray jsegs = ToJava(env, pmi.segments.data(), pmi.segments.size());
  jintArray jitems = ToJava(env, items);
  jfloatArray janchors = ToJava(env, anchors.data(), anchors.size());
  jbyteArray jtexts = env->NewByteArray(static_cast<jsize>(texts.size()));
  if (!jsegs || !jitems || !janchors || !jtexts) return nullptr;
  env->SetByteArrayRegion(jtexts, 0, static_cast<jsize>(texts.size()),
                          reinterpret_cast<const jbyte*>(texts.data()));
  env->SetObjectArrayElement(result, 0, jsegs);
  env->SetObjectArrayElement(result, 1, jitems);
  env->SetObjectArrayElement(result, 2, janchors);
  env->SetObjectArrayElement(result, 3, jtexts);
  return result;
}

// Delik tablosu (yalnızca CAD dosyaları).
// Dönüş: [double[] delik başına 17 değer, int[] yüzey kimlikleri] veya null.
// Değerler: parça, çap, derinlik, boydan boya (1/0), matkap ucu (1/0), kaçak çapı, kaçak derinliği,
// konik havşa çapı, konik havşa açısı, giriş xyz, yön xyz, yüzey başlangıcı, yüzey sayısı.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_mertkaradag_stepviewer_StepNative_holes(JNIEnv* env, jobject /*thiz*/) {
  std::vector<TopoDS_Face> faces;
  std::vector<TopoDS_Shape> parts;
  std::vector<int32_t> offsets;
  {
    std::lock_guard<std::mutex> lock(gStore.mutex);
    if (gStore.mesh || gStore.faces.empty()) return nullptr;
    faces = gStore.faces;
    parts = gStore.parts;
    offsets = gStore.partFaceOffsets;
  }
  std::vector<stepviewer::HoleInfo> holes;
  try {
    stepviewer::FindHoles(faces, offsets, parts, holes);
  } catch (const Standard_Failure&) {
    return nullptr;
  }
  std::vector<double> values;
  std::vector<int32_t> faceIds;
  values.reserve(holes.size() * 17);
  for (const auto& h : holes) {
    values.insert(values.end(),
                  {static_cast<double>(h.part), h.diameter, h.depth, h.through ? 1.0 : 0.0,
                   h.drillPoint ? 1.0 : 0.0, h.cboreDiameter, h.cboreDepth, h.csinkDiameter,
                   h.csinkAngle, h.entry[0], h.entry[1], h.entry[2], h.dir[0], h.dir[1], h.dir[2],
                   static_cast<double>(faceIds.size()), static_cast<double>(h.faces.size())});
    faceIds.insert(faceIds.end(), h.faces.begin(), h.faces.end());
  }
  jclass objClass = env->FindClass("java/lang/Object");
  if (objClass == nullptr) return nullptr;
  jobjectArray result = env->NewObjectArray(2, objClass, nullptr);
  if (result == nullptr) return nullptr;
  jdoubleArray jv = ToJava(env, values);
  jintArray jf = ToJava(env, faceIds);
  if (!jv || !jf) return nullptr;
  env->SetObjectArrayElement(result, 0, jv);
  env->SetObjectArrayElement(result, 1, jf);
  return result;
}

// Düz yüzü DXF olarak (yalnızca CAD). Dönüş: [byte[] DXF metni, double[] {genişlik, yükseklik,
// iç kontur sayısı}] veya yüz düz değilse null.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_mertkaradag_stepviewer_StepNative_faceDxf(JNIEnv* env, jobject /*thiz*/, jint faceId) {
  TopoDS_Shape shape;
  {
    std::lock_guard<std::mutex> lock(gStore.mutex);
    if (gStore.mesh) return nullptr;
  }
  if (!GetStoredShape(kKindFace, faceId, shape)) return nullptr;
  stepviewer::DxfResult r;
  if (!stepviewer::ExportFaceDxf(TopoDS::Face(shape), r)) return nullptr;
  jclass objClass = env->FindClass("java/lang/Object");
  if (objClass == nullptr) return nullptr;
  jobjectArray result = env->NewObjectArray(2, objClass, nullptr);
  if (result == nullptr) return nullptr;
  jbyteArray jtext = env->NewByteArray(static_cast<jsize>(r.text.size()));
  jdoubleArray jinfo = ToJava(env, std::vector<double>{r.width, r.height, static_cast<double>(r.innerLoops)});
  if (!jtext || !jinfo) return nullptr;
  env->SetByteArrayRegion(jtext, 0, static_cast<jsize>(r.text.size()),
                          reinterpret_cast<const jbyte*>(r.text.data()));
  env->SetObjectArrayElement(result, 0, jtext);
  env->SetObjectArrayElement(result, 1, jinfo);
  return result;
}

// Teknik resim görünüşleri (yalnızca CAD). partIds null ise tüm parçalar.
// Dönüş: [float[] segmentler (x1, y1, x2, y2), int[] görünüş başına (görünenBaş, görünenAdet,
// gizliBaş, gizliAdet) * 4] veya null.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_mertkaradag_stepviewer_StepNative_drawingViews(JNIEnv* env, jobject /*thiz*/,
                                                        jintArray jpartIds, jboolean zUp,
                                                        jboolean hidden) {
  std::vector<TopoDS_Shape> shapes;
  {
    std::lock_guard<std::mutex> lock(gStore.mutex);
    if (gStore.mesh || gStore.parts.empty()) return nullptr;
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
  std::vector<stepviewer::DrawingView> views;
  try {
    if (!stepviewer::MakeDrawingViews(shapes, zUp, hidden, views)) return nullptr;
  } catch (const Standard_Failure&) {
    return nullptr;
  }
  std::vector<float> segs;
  std::vector<int32_t> ranges;
  for (const auto& v : views) {
    ranges.push_back(static_cast<int32_t>(segs.size() / 4));
    ranges.push_back(static_cast<int32_t>(v.visible.size() / 4));
    segs.insert(segs.end(), v.visible.begin(), v.visible.end());
    ranges.push_back(static_cast<int32_t>(segs.size() / 4));
    ranges.push_back(static_cast<int32_t>(v.hidden.size() / 4));
    segs.insert(segs.end(), v.hidden.begin(), v.hidden.end());
  }
  jclass objClass = env->FindClass("java/lang/Object");
  if (objClass == nullptr) return nullptr;
  jobjectArray result = env->NewObjectArray(2, objClass, nullptr);
  if (result == nullptr) return nullptr;
  jfloatArray js = ToJava(env, segs.data(), segs.size());
  jintArray jr = ToJava(env, ranges);
  if (!js || !jr) return nullptr;
  env->SetObjectArrayElement(result, 0, js);
  env->SetObjectArrayElement(result, 1, jr);
  return result;
}
