// Teknik resim görünüşleri: gizli çizgi kaldırma (OCCT HLR) ile 2D çizgiler.
#pragma once

#include <TopoDS_Shape.hxx>

#include <vector>

namespace stepviewer {

// Görünüş sırası: 0 ön, 1 üst, 2 sol (birinci açı yönteminde sağa konur), 3 izometrik.
constexpr int kDrawingViewCount = 4;

struct DrawingView {
  std::vector<float> visible;  // görünen çizgiler: segment başına x1, y1, x2, y2 (mm, görünüş düzleminde)
  std::vector<float> hidden;   // gizli çizgiler (kesikli çizilir)
};

// Model Z yukarı kabul edilir; zUp false ise Y yukarı.
// Şekiller önceden üçgenlenmiş olmalıdır (yükleme sırasında yapılır).
bool MakeDrawingViews(const std::vector<TopoDS_Shape>& shapes, bool zUp, bool withHidden,
                      std::vector<DrawingView>& out);

}  // namespace stepviewer
