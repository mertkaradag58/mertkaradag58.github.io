// Düz bir yüzün sınırlarını (dış kontur + delikler) lazer / plazma kesim için 2D DXF'e yazar.
#pragma once

#include <TopoDS_Face.hxx>

#include <string>

namespace stepviewer {

struct DxfResult {
  std::string text;      // DXF (R12 ASCII)
  double width = 0;      // kontur genişliği, mm
  double height = 0;     // kontur yüksekliği, mm
  int innerLoops = 0;    // iç kontur (delik, pencere) sayısı
};

// Yüz düz değilse veya okunamazsa false döner. Görünüm yüzün dış normali yönünden bakılmış gibidir
// (ayna görüntüsü olmaz); sol alt köşe (0, 0) noktasına taşınır. Birim mm.
bool ExportFaceDxf(const TopoDS_Face& face, DxfResult& out);

}  // namespace stepviewer
