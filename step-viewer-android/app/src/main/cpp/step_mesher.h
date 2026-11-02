// CAD dosyası (STEP / IGES / BREP) ve mesh dosyası (OBJ, STL, glTF, DAE, 3DS, FBX, PLY)
// okuma, üçgenleme ve ölçüm çekirdeği.
// JNI'den bağımsızdır; masaüstünde host_test ile de derlenip denenebilir.
#pragma once

#include <TopoDS_Edge.hxx>
#include <TopoDS_Face.hxx>
#include <TopoDS_Shape.hxx>

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace stepviewer {

// Tek bir köşe noktası için 9 float: konum (3), normal (3), renk RGB (3).
constexpr int kFloatsPerVertex = 9;

// Sayısal değerler Kotlin tarafındaki StepMesh.FORMAT_* ile aynı olmalıdır.
enum class FileFormat { Unknown, Step, Iges, Brep, Obj, Stl, Gltf, Dae, Max3ds, Fbx, Ply };

inline bool IsMeshFormat(FileFormat f) { return static_cast<int>(f) >= static_cast<int>(FileFormat::Obj); }

// Mesh (üçgen) dosyalarında ölçüm için saklanan geometri. CAD dosyalarında kullanılmaz.
// Konumlar mm cinsindendir ve montaj konumu uygulanmıştır.
struct MeshGeometry {
  std::vector<float> pos;              // kaynaştırılmış köşe konumları (xyz)
  std::vector<uint32_t> tris;          // üçgenler, MeshData::indices ile aynı sırada (pos indeksleri)
  std::vector<int32_t> faceOffsets;    // yüzey i -> üçgenler
  std::vector<int32_t> partFaceOffsets;
  std::vector<float> edgePts;          // kenar çoklu çizgilerinin noktaları (xyz)
  std::vector<int32_t> edgePtOffsets;  // kenar i -> noktalar [o[i], o[i+1])
  std::vector<uint8_t> partClosed;     // parça kapalı (su geçirmez) mi: hacim hesaplanabilir
  double tol = 1e-6;                   // geometrik tolerans (mm)
};

// Montaj ağacındaki bir düğüm (derinlik öncelikli sırada).
struct TreeNode {
  std::string name;      // UTF-8
  int depth = 0;         // kök = 0
  int leafStart = 0;     // bu düğümün altındaki parçalar: [leafStart, leafEnd)
  int leafEnd = 0;
  bool isAssembly = false;
};

struct MeshData {
  std::vector<float> vertices;    // kFloatsPerVertex * köşe sayısı
  std::vector<uint32_t> indices;  // üçgen indeksleri (3 * üçgen sayısı)
  std::vector<float> edges;       // kenar çizgileri: segment başına 2 nokta * 3 float

  // Aralık tabloları (her biri eleman sayısı + 1 uzunluğunda, ilk değer 0):
  std::vector<int32_t> faceOffsets;      // yüzey i -> üçgenler [faceOffsets[i], faceOffsets[i+1])
  std::vector<int32_t> edgeOffsets;      // kenar i -> segmentler
  std::vector<int32_t> partFaceOffsets;  // parça i -> yüzey kimlikleri
  std::vector<int32_t> partEdgeOffsets;  // parça i -> kenar kimlikleri

  std::vector<TreeNode> tree;

  // Ölçüm için yerleştirilmiş (montaj konumu uygulanmış) gerçek geometriler.
  std::vector<TopoDS_Face> faceShapes;
  std::vector<TopoDS_Edge> edgeShapes;
  std::vector<TopoDS_Shape> partShapes;

  // Mesh dosyalarında dolu; o zaman yukarıdaki şekiller boştur (yalnızca sayı tutar).
  std::shared_ptr<MeshGeometry> meshGeometry;

  float bboxMin[3] = {0, 0, 0};
  float bboxMax[3] = {0, 0, 0};
  FileFormat format = FileFormat::Unknown;

  size_t vertexCount() const { return vertices.size() / kFloatsPerVertex; }
  size_t triangleCount() const { return indices.size() / 3; }
  int partCount() const { return static_cast<int>(partShapes.size()); }
  int faceCount() const { return static_cast<int>(faceShapes.size()); }
};

struct MeshOptions {
  // Doğrusal sapma, modelin köşegen uzunluğuna oranla.
  double relativeDeflection = 0.001;
  // Açısal sapma (radyan).
  double angularDeflection = 0.35;
  bool extractEdges = true;
};

// Dosyanın içeriğine bakarak biçimini bulur.
FileFormat DetectFormat(const std::string& path);

// Dosyayı okur, üçgenler ve sonucu `out` içine yazar.
// Başarısızlıkta false döner ve `error` içine açıklama yazar.
bool LoadModelFile(const std::string& path, const MeshOptions& options, MeshData& out,
                   std::string& error);

// --- Ölçüm ---

enum class GeomKind : int {
  Other = 0,
  // kenarlar
  Line = 1,
  Circle = 2,
  Ellipse = 3,
  Curve = 4,
  // yüzeyler
  Plane = 10,
  Cylinder = 11,
  Cone = 12,
  Sphere = 13,
  Torus = 14,
  Surface = 15,
};

struct ShapeInfo {
  GeomKind kind = GeomKind::Other;
  double length = 0;             // kenar uzunluğu
  double area = 0;               // yüzey alanı
  double radius = 0;             // çember / silindir / küre / koni tabanı yarıçapı
  double radius2 = 0;            // elips küçük yarıçapı veya torus küçük yarıçapı
  double angleDeg = 0;           // koni yarı açısı
  double center[3] = {0, 0, 0};  // merkez veya eksen üzerindeki nokta / düzlem noktası
  double dir[3] = {0, 0, 0};     // doğru yönü, eksen yönü veya düzlem normali
};

// Kenar veya yüzey için geometri bilgisi.
bool GetShapeInfo(const TopoDS_Shape& shape, ShapeInfo& info);

struct PairMeasure {
  double distance = 0;           // en kısa mesafe (sınırlı geometriler arası)
  double p1[3] = {0, 0, 0};      // birinci şekildeki en yakın nokta
  double p2[3] = {0, 0, 0};      // ikinci şekildeki en yakın nokta
  double angleDeg = -1;          // doğru/düzlem/eksen arası açı (0..90), yoksa -1
  double centerDistance = -1;    // çember merkezleri veya paralel eksenler arası, yoksa -1
  double parallelDistance = -1;  // paralel düzlemler arası dik mesafe, yoksa -1
};

bool MeasurePair(const TopoDS_Shape& a, const TopoDS_Shape& b, PairMeasure& out);

struct MassProps {
  double volume = 0;             // mm³ (yalnızca katılar)
  double area = 0;               // mm²
  double center[3] = {0, 0, 0};  // hacim merkezi (katı yoksa alan merkezi)
  bool hasSolids = false;
};

bool ComputeMassProps(const std::vector<TopoDS_Shape>& shapes, MassProps& out);

// MeasurePair'in bilgileri önceden hesaplanmış hali (ia/ib null olabilir).
bool MeasurePairWithInfo(const TopoDS_Shape& a, const ShapeInfo* ia, const TopoDS_Shape& b,
                         const ShapeInfo* ib, PairMeasure& out);

// --- Mesh dosyaları için ölçüm (kind: 0 = kenar, 1 = yüzey) ---

bool MeshFeatureInfo(const MeshGeometry& g, int kind, int id, ShapeInfo& info);
bool MeshMeasurePair(const MeshGeometry& g, int kindA, int idA, int kindB, int idB,
                     PairMeasure& out);
// partIds null ise tüm model.
bool MeshMassProps(const MeshGeometry& g, const std::vector<int>* partIds, MassProps& out);

}  // namespace stepviewer
