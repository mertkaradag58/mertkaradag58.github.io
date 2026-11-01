// STEP okuma ve üçgenleme çekirdeği.
// JNI'den bağımsızdır; masaüstünde host_test ile de derlenip denenebilir.
#pragma once

#include <TopoDS_Edge.hxx>

#include <cstdint>
#include <string>
#include <vector>

namespace stepviewer {

// Tek bir köşe noktası için 9 float: konum (3), normal (3), renk RGB (3).
constexpr int kFloatsPerVertex = 9;

struct MeshData {
  std::vector<float> vertices;    // kFloatsPerVertex * köşe sayısı
  std::vector<uint32_t> indices;  // üçgen indeksleri (3 * üçgen sayısı)
  std::vector<float> edges;       // kenar çizgileri: segment başına 2 nokta * 3 float
  // edgeOffsets[i] .. edgeOffsets[i+1]: i numaralı kenarın segment aralığı (boyut: kenar sayısı + 1)
  std::vector<int32_t> edgeOffsets;
  // Ölçüm için yerleştirilmiş (konumu uygulanmış) gerçek kenar geometrileri; indeks = kenar kimliği
  std::vector<TopoDS_Edge> edgeShapes;
  float bboxMin[3] = {0, 0, 0};
  float bboxMax[3] = {0, 0, 0};
  int faceCount = 0;
  int partCount = 0;

  size_t vertexCount() const { return vertices.size() / kFloatsPerVertex; }
  size_t triangleCount() const { return indices.size() / 3; }
};

struct MeshOptions {
  // Doğrusal sapma, modelin köşegen uzunluğuna oranla.
  double relativeDeflection = 0.001;
  // Açısal sapma (radyan).
  double angularDeflection = 0.35;
  bool extractEdges = true;
};

// STEP dosyasını okur, üçgenler ve sonucu `out` içine yazar.
// Başarısızlıkta false döner ve `error` içine açıklama yazar.
bool LoadStepFile(const std::string& path, const MeshOptions& options,
                  MeshData& out, std::string& error);

// --- Ölçüm ---

enum class EdgeType : int { Other = 0, Line = 1, Circle = 2, Ellipse = 3, BSpline = 4 };

struct EdgeInfo {
  EdgeType type = EdgeType::Other;
  double length = 0;     // kenar uzunluğu (çember yayı için yay uzunluğu)
  double radius = 0;     // çember için yarıçap
  double center[3] = {0, 0, 0};  // çember merkezi
  double dir[3] = {0, 0, 0};     // doğru yönü veya çember ekseni
};

bool GetEdgeInfo(const TopoDS_Edge& edge, EdgeInfo& info);

struct EdgeDistance {
  double distance = 0;               // en kısa mesafe
  double p1[3] = {0, 0, 0};          // birinci kenardaki en yakın nokta
  double p2[3] = {0, 0, 0};          // ikinci kenardaki en yakın nokta
  double angleDeg = -1;              // iki doğru arasındaki açı (0..90), yoksa -1
  double centerDistance = -1;        // iki çember merkezi arası mesafe, yoksa -1
};

bool MeasureEdges(const TopoDS_Edge& a, const TopoDS_Edge& b, EdgeDistance& out);

}  // namespace stepviewer
