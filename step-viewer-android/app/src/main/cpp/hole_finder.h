// Delik tablosu: CAD modelindeki silindirik delikleri (havşa ve kaçak dahil) bulur.
#pragma once

#include <TopoDS_Face.hxx>
#include <TopoDS_Shape.hxx>

#include <cstdint>
#include <vector>

namespace stepviewer {

struct HoleInfo {
  int part = 0;
  double diameter = 0;     // ana (en küçük) çap, mm
  double depth = 0;        // ana çaplı bölümün boyu, mm
  bool through = false;    // boydan boya mı
  bool drillPoint = false; // kör delik ucunda matkap konisi var mı
  double cboreDiameter = 0;  // silindirik havşa (kaçak) çapı, yoksa 0
  double cboreDepth = 0;
  double csinkDiameter = 0;  // konik havşa dış çapı, yoksa 0
  double csinkAngle = 0;     // konik havşa tam açısı (derece)
  double entry[3] = {0, 0, 0};  // giriş ağzının eksen üzerindeki noktası
  double dir[3] = {0, 0, 1};    // girişten içeri doğru eksen yönü
  std::vector<int32_t> faces;   // deliği oluşturan yüzey kimlikleri
};

// faces: tüm yüzeyler (yerleştirilmiş), partFaceOffsets: parça i -> yüzeyler
// [o[i], o[i+1]), parts: parça şekilleri (içerde/dışarıda testi için).
void FindHoles(const std::vector<TopoDS_Face>& faces, const std::vector<int32_t>& partFaceOffsets,
               const std::vector<TopoDS_Shape>& parts, std::vector<HoleInfo>& out);

}  // namespace stepviewer
