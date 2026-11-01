// STEP okuma ve üçgenleme çekirdeği.
// JNI'den bağımsızdır; masaüstünde host_test ile de derlenip denenebilir.
#pragma once

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

}  // namespace stepviewer
