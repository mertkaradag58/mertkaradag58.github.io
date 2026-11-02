// STEP AP242 PMI (ölçü, geometrik tolerans, referans) okuma.
#pragma once

#include <TDocStd_Document.hxx>

#include <cstdint>
#include <string>
#include <vector>

namespace stepviewer {

// Kotlin tarafındaki Pmi.KIND_* ile aynı değerler.
enum class PmiKind : int { Dimension = 0, Tolerance = 1, Datum = 2 };

struct PmiItem {
  PmiKind kind = PmiKind::Dimension;
  std::string text;      // UTF-8, ör. "Ø 12 H7", "⌖ 0,05 Ⓜ | A | B"
  int32_t segStart = 0;  // çizim çizgileri: PmiData::segments içinde [segStart, segEnd) segment
  int32_t segEnd = 0;
  float anchor[3] = {0, 0, 0};  // yazı konumu veya çizimin merkezi
  bool hasAnchor = false;
};

struct PmiData {
  std::vector<PmiItem> items;
  std::vector<float> segments;  // segment başına 2 nokta * 3 float
};

// Belgedeki PMI'yi okur. linDefl: eğri çizgileri parçalara bölme hassasiyeti (mm).
void ExtractPmi(const Handle(TDocStd_Document)& doc, double linDefl, PmiData& out);

}  // namespace stepviewer
