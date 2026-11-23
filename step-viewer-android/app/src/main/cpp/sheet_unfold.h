// Sac açınımı: bükülmüş sac parçayı düz hale açar (K faktörü ile), büküm tablosu ve kesim DXF'i
// üretir. Desteklenen: düz flanşlar + silindirik bükümler (sabit kalınlık).
#pragma once

#include <TopoDS_Face.hxx>
#include <TopoDS_Shape.hxx>

#include <string>
#include <vector>

namespace stepviewer {

struct SheetBend {
  double angleDeg = 0;     // büküm açısı (düzden sapma), derece
  double innerRadius = 0;  // iç yarıçap, mm
  double length = 0;       // büküm çizgisi boyu, mm
  double allowance = 0;    // büküm payı (nötr eksen yay boyu), mm
  bool up = true;          // açınıma üstten bakınca flanş bakana doğru (yukarı) mı bükülür
  double x1 = 0, y1 = 0, x2 = 0, y2 = 0;  // açınımdaki büküm çizgisi (orta), mm
};

enum class SheetStatus {
  Ok = 0,
  NotPlanar = 1,    // seçilen yüz düz değil
  NoThickness = 2,  // karşı yüz bulunamadı, sac değil
  Failed = 3,       // geometri hatası
};

struct SheetResult {
  SheetStatus status = SheetStatus::Failed;
  double thickness = 0;
  double kFactor = 0;
  double width = 0, height = 0;  // açınım dış ölçüleri, mm
  double area = 0;               // açınım alanı (delikler düşülmüş), mm²
  int skippedFaces = 0;          // açılamayan (koni, serbest yüzey...) teğet yüz sayısı
  std::vector<SheetBend> bends;
  std::vector<float> outline;    // önizleme: kesim çizgileri (x1, y1, x2, y2)
  std::vector<float> bendLines;  // önizleme: büküm çizgileri (x1, y1, x2, y2)
  std::string dxf;               // DXF R12, mm; katmanlar KESIM, BUKUM, NOT
};

// part: sac parça (yerleştirilmiş). baseFace: açınımın düz kalacak yüzü (part içinde olmalı);
// null ise en büyük düz yüz seçilir. kFactor: nötr eksen oranı (0..1, tipik 0,33-0,5).
void UnfoldSheet(const TopoDS_Shape& part, const TopoDS_Face& baseFace, double kFactor, SheetResult& out);

}  // namespace stepviewer
