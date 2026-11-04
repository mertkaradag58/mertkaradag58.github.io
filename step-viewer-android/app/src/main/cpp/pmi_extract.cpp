#include "pmi_extract.h"

#include <BRepAdaptor_Curve.hxx>
#include <BRep_Tool.hxx>
#include <Bnd_Box.hxx>
#include <BRepBndLib.hxx>
#include <GCPnts_TangentialDeflection.hxx>
#include <Poly_Polygon3D.hxx>
#include <Poly_Triangulation.hxx>
#include <Standard_Failure.hxx>
#include <TCollection_HAsciiString.hxx>
#include <TDF_Label.hxx>
#include <TDF_LabelSequence.hxx>
#include <TopExp_Explorer.hxx>
#include <TopLoc_Location.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Edge.hxx>
#include <TopoDS_Face.hxx>
#include <XCAFDimTolObjects_DatumObject.hxx>
#include <XCAFDimTolObjects_DimensionObject.hxx>
#include <XCAFDimTolObjects_GeomToleranceObject.hxx>
#include <XCAFDoc_Datum.hxx>
#include <XCAFDoc_DimTolTool.hxx>
#include <XCAFDoc_Dimension.hxx>
#include <XCAFDoc_DocumentTool.hxx>
#include <XCAFDoc_GeomTolerance.hxx>

#include <cctype>
#include <cmath>
#include <cstdio>
#include <set>
#include <utility>

namespace stepviewer {
namespace {

// Değeri gereksiz sıfırlar olmadan yazar: 12 -> "12", 0.050 -> "0.05".
std::string Num(double v) {
  char buf[64];
  std::snprintf(buf, sizeof(buf), "%.4f", v);
  std::string s(buf);
  if (s.find('.') != std::string::npos) {
    while (!s.empty() && s.back() == '0') s.pop_back();
    if (!s.empty() && s.back() == '.') s.pop_back();
  }
  if (s == "-0") s = "0";
  return s;
}

std::string Signed(double v) { return (v >= 0 ? "+" : "") + Num(v); }

std::string Ascii(const Handle(TCollection_HAsciiString)& s) {
  if (s.IsNull()) return std::string();
  return std::string(s->ToCString());
}

const char* const kFormVariance[] = {
    "",  "A", "B", "C", "CD", "D", "E", "EF", "F", "FG", "G", "H", "JS", "J", "K",
    "M", "N", "P", "R", "S", "T", "U", "V", "X", "Y", "Z", "ZA", "ZB", "ZC"};

std::string DimensionPrefix(XCAFDimTolObjects_DimensionType t) {
  switch (t) {
    case XCAFDimTolObjects_DimensionType_Size_Diameter:
    case XCAFDimTolObjects_DimensionType_Size_ToroidalMinorDiameter:
    case XCAFDimTolObjects_DimensionType_Size_ToroidalMajorDiameter:
    case XCAFDimTolObjects_DimensionType_Size_ToroidalHighMajorDiameter:
    case XCAFDimTolObjects_DimensionType_Size_ToroidalLowMajorDiameter:
      return "\xC3\x98 ";  // Ø
    case XCAFDimTolObjects_DimensionType_Size_SphericalDiameter:
      return "S\xC3\x98 ";
    case XCAFDimTolObjects_DimensionType_Size_Radius:
    case XCAFDimTolObjects_DimensionType_Size_ToroidalMinorRadius:
    case XCAFDimTolObjects_DimensionType_Size_ToroidalMajorRadius:
    case XCAFDimTolObjects_DimensionType_Size_ToroidalHighMajorRadius:
    case XCAFDimTolObjects_DimensionType_Size_ToroidalLowMajorRadius:
      return "R ";
    case XCAFDimTolObjects_DimensionType_Size_SphericalRadius:
      return "SR ";
    default:
      return "";
  }
}

bool IsAngular(XCAFDimTolObjects_DimensionType t) {
  return t == XCAFDimTolObjects_DimensionType_Location_Angular ||
         t == XCAFDimTolObjects_DimensionType_Size_Angular;
}

std::string DimensionText(const Handle(XCAFDimTolObjects_DimensionObject)& obj) {
  const XCAFDimTolObjects_DimensionType t = obj->GetType();
  std::string s = DimensionPrefix(t);
  const char* unit = IsAngular(t) ? "\xC2\xB0" : "";  // °
  if (obj->IsDimWithRange()) {
    s += Num(obj->GetLowerBound()) + unit + " - " + Num(obj->GetUpperBound()) + unit;
    return s;
  }
  s += Num(obj->GetValue()) + unit;
  if (obj->IsDimWithPlusMinusTolerance()) {
    const double up = obj->GetUpperTolValue();
    const double lo = obj->GetLowerTolValue();
    if (std::fabs(up + lo) < 1e-12 && up > 0) {
      s += " \xC2\xB1" + Num(up);  // ±
    } else {
      s += " " + Signed(up) + "/" + Signed(lo);
    }
  } else if (obj->IsDimWithClassOfTolerance()) {
    Standard_Boolean hole = Standard_False;
    XCAFDimTolObjects_DimensionFormVariance fv = XCAFDimTolObjects_DimensionFormVariance_None;
    XCAFDimTolObjects_DimensionGrade grade = XCAFDimTolObjects_DimensionGrade_IT01;
    if (obj->GetClassOfTolerance(hole, fv, grade)) {
      std::string letters;
      const int fi = static_cast<int>(fv);
      if (fi > 0 && fi < static_cast<int>(sizeof(kFormVariance) / sizeof(kFormVariance[0]))) {
        letters = kFormVariance[fi];
      }
      if (!hole) {
        for (char& c : letters) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
      }
      const int g = static_cast<int>(grade);
      const std::string gradeText = g == 0 ? "01" : std::to_string(g - 1);
      if (!letters.empty()) s += " " + letters + gradeText;
    }
  }
  return s;
}

const char* ToleranceSymbol(XCAFDimTolObjects_GeomToleranceType t) {
  switch (t) {
    case XCAFDimTolObjects_GeomToleranceType_Angularity: return "\xE2\x88\xA0";        // ∠
    case XCAFDimTolObjects_GeomToleranceType_CircularRunout: return "\xE2\x86\x97";    // ↗
    case XCAFDimTolObjects_GeomToleranceType_CircularityOrRoundness: return "\xE2\x97\x8B";  // ○
    case XCAFDimTolObjects_GeomToleranceType_Coaxiality:
    case XCAFDimTolObjects_GeomToleranceType_Concentricity: return "\xE2\x97\x8E";     // ◎
    case XCAFDimTolObjects_GeomToleranceType_Cylindricity: return "\xE2\x8C\xAD";      // ⌭
    case XCAFDimTolObjects_GeomToleranceType_Flatness: return "\xE2\x8F\xA5";          // ⏥
    case XCAFDimTolObjects_GeomToleranceType_Parallelism: return "\xE2\x88\xA5";       // ∥
    case XCAFDimTolObjects_GeomToleranceType_Perpendicularity: return "\xE2\x9F\x82";  // ⟂
    case XCAFDimTolObjects_GeomToleranceType_Position: return "\xE2\x8C\x96";          // ⌖
    case XCAFDimTolObjects_GeomToleranceType_ProfileOfLine: return "\xE2\x8C\x92";     // ⌒
    case XCAFDimTolObjects_GeomToleranceType_ProfileOfSurface: return "\xE2\x8C\x93";  // ⌓
    case XCAFDimTolObjects_GeomToleranceType_Straightness: return "\xE2\x80\x95";      // ―
    case XCAFDimTolObjects_GeomToleranceType_Symmetry: return "\xE2\x8C\xAF";          // ⌯
    case XCAFDimTolObjects_GeomToleranceType_TotalRunout: return "\xE2\x8C\xB0";       // ⌰
    default: return "";
  }
}

std::string DatumName(const TDF_Label& label) {
  Handle(XCAFDoc_Datum) attr;
  if (!label.FindAttribute(XCAFDoc_Datum::GetID(), attr) || attr.IsNull()) return std::string();
  Handle(XCAFDimTolObjects_DatumObject) obj = attr->GetObject();
  if (obj.IsNull()) return std::string();
  return Ascii(obj->GetName());
}

std::string ToleranceText(const TDF_Label& label,
                          const Handle(XCAFDimTolObjects_GeomToleranceObject)& obj) {
  std::string s = ToleranceSymbol(obj->GetType());
  if (!s.empty()) s += " ";
  if (obj->GetTypeOfValue() == XCAFDimTolObjects_GeomToleranceTypeValue_Diameter) {
    s += "\xC3\x98";
  } else if (obj->GetTypeOfValue() == XCAFDimTolObjects_GeomToleranceTypeValue_SphericalDiameter) {
    s += "S\xC3\x98";
  }
  s += Num(obj->GetValue());
  switch (obj->GetMaterialRequirementModifier()) {
    case XCAFDimTolObjects_GeomToleranceMatReqModif_M: s += " (M)"; break;
    case XCAFDimTolObjects_GeomToleranceMatReqModif_L: s += " (L)"; break;
    default: break;
  }
  TDF_LabelSequence datums;
  if (XCAFDoc_DimTolTool::GetDatumOfTolerLabels(label, datums)) {
    for (TDF_LabelSequence::Iterator it(datums); it.More(); it.Next()) {
      const std::string name = DatumName(it.Value());
      if (!name.empty()) s += " | " + name;
    }
  }
  return s;
}

// Çizim şeklini (çoklu çizgiler, bazen üçgenlenmiş yazı) segmentlere çevirir.
void AppendPresentation(const TopoDS_Shape& shape, double linDefl, PmiData& out, Bnd_Box& box) {
  if (shape.IsNull()) return;
  auto addSeg = [&](const gp_Pnt& a, const gp_Pnt& b) {
    out.segments.insert(out.segments.end(),
                        {static_cast<float>(a.X()), static_cast<float>(a.Y()),
                         static_cast<float>(a.Z()), static_cast<float>(b.X()),
                         static_cast<float>(b.Y()), static_cast<float>(b.Z())});
    box.Add(a);
    box.Add(b);
  };

  for (TopExp_Explorer ex(shape, TopAbs_EDGE); ex.More(); ex.Next()) {
    const TopoDS_Edge& edge = TopoDS::Edge(ex.Current());
    try {
      TopLoc_Location loc;
      Handle(Poly_Polygon3D) poly = BRep_Tool::Polygon3D(edge, loc);
      if (!poly.IsNull() && poly->NbNodes() >= 2) {
        const gp_Trsf tr = loc.Transformation();
        for (int i = 1; i < poly->NbNodes(); ++i) {
          addSeg(poly->Nodes().Value(i).Transformed(tr), poly->Nodes().Value(i + 1).Transformed(tr));
        }
        continue;
      }
      if (BRep_Tool::Degenerated(edge)) continue;
      Standard_Real f = 0, l = 0;
      if (BRep_Tool::Curve(edge, f, l).IsNull()) continue;
      BRepAdaptor_Curve curve(edge);
      GCPnts_TangentialDeflection pts(curve, 0.2, linDefl);
      for (int i = 1; i < pts.NbPoints(); ++i) addSeg(pts.Value(i), pts.Value(i + 1));
    } catch (const Standard_Failure&) {
      // bozuk eğri: atla
    }
  }

  // Üçgenlenmiş yazılar (tessellated annotation): üçgen kenarları
  for (TopExp_Explorer ex(shape, TopAbs_FACE); ex.More(); ex.Next()) {
    const TopoDS_Face& face = TopoDS::Face(ex.Current());
    TopLoc_Location loc;
    Handle(Poly_Triangulation) tri = BRep_Tool::Triangulation(face, loc);
    if (tri.IsNull()) continue;
    const gp_Trsf tr = loc.Transformation();
    std::set<std::pair<int, int>> seen;
    for (int t = 1; t <= tri->NbTriangles(); ++t) {
      int n[3];
      tri->Triangle(t).Get(n[0], n[1], n[2]);
      for (int k = 0; k < 3; ++k) {
        int a = n[k], b = n[(k + 1) % 3];
        if (a > b) std::swap(a, b);
        if (!seen.insert({a, b}).second) continue;
        addSeg(tri->Node(a).Transformed(tr), tri->Node(b).Transformed(tr));
      }
    }
  }
}

void FinishItem(PmiItem& item, const Bnd_Box& box, bool hasText, const gp_Pnt& text,
                PmiData& out) {
  item.segEnd = static_cast<int32_t>(out.segments.size() / 6);
  if (hasText) {
    item.anchor[0] = static_cast<float>(text.X());
    item.anchor[1] = static_cast<float>(text.Y());
    item.anchor[2] = static_cast<float>(text.Z());
    item.hasAnchor = true;
  } else if (!box.IsVoid()) {
    double x0, y0, z0, x1, y1, z1;
    box.Get(x0, y0, z0, x1, y1, z1);
    item.anchor[0] = static_cast<float>((x0 + x1) / 2);
    item.anchor[1] = static_cast<float>((y0 + y1) / 2);
    item.anchor[2] = static_cast<float>((z0 + z1) / 2);
    item.hasAnchor = true;
  }
  out.items.push_back(item);
}

}  // namespace

void ExtractPmi(const Handle(TDocStd_Document)& doc, double linDefl, PmiData& out) {
  if (doc.IsNull()) return;
  Handle(XCAFDoc_DimTolTool) tool = XCAFDoc_DocumentTool::DimTolTool(doc->Main());
  if (tool.IsNull()) return;

  try {
    TDF_LabelSequence dims;
    tool->GetDimensionLabels(dims);
    for (TDF_LabelSequence::Iterator it(dims); it.More(); it.Next()) {
      Handle(XCAFDoc_Dimension) attr;
      if (!it.Value().FindAttribute(XCAFDoc_Dimension::GetID(), attr) || attr.IsNull()) continue;
      Handle(XCAFDimTolObjects_DimensionObject) obj = attr->GetObject();
      if (obj.IsNull()) continue;
      PmiItem item;
      item.kind = PmiKind::Dimension;
      const XCAFDimTolObjects_DimensionType t = obj->GetType();
      if (t == XCAFDimTolObjects_DimensionType_CommonLabel ||
          t == XCAFDimTolObjects_DimensionType_DimensionPresentation) {
        // Yalnızca çizim (anlamsal değer yok): sunum adını kullan
        item.text = Ascii(obj->GetPresentationName());
        if (item.text.empty()) item.text = Ascii(obj->GetSemanticName());
      } else {
        item.text = DimensionText(obj);
      }
      item.segStart = static_cast<int32_t>(out.segments.size() / 6);
      Bnd_Box box;
      AppendPresentation(obj->GetPresentation(), linDefl, out, box);
      FinishItem(item, box, obj->HasTextPoint(), obj->GetPointTextAttach(), out);
    }

    TDF_LabelSequence tols;
    tool->GetGeomToleranceLabels(tols);
    for (TDF_LabelSequence::Iterator it(tols); it.More(); it.Next()) {
      Handle(XCAFDoc_GeomTolerance) attr;
      if (!it.Value().FindAttribute(XCAFDoc_GeomTolerance::GetID(), attr) || attr.IsNull()) continue;
      Handle(XCAFDimTolObjects_GeomToleranceObject) obj = attr->GetObject();
      if (obj.IsNull()) continue;
      PmiItem item;
      item.kind = PmiKind::Tolerance;
      item.text = ToleranceText(it.Value(), obj);
      item.segStart = static_cast<int32_t>(out.segments.size() / 6);
      Bnd_Box box;
      AppendPresentation(obj->GetPresentation(), linDefl, out, box);
      FinishItem(item, box, obj->HasPointText(), obj->GetPointTextAttach(), out);
    }

    TDF_LabelSequence datums;
    tool->GetDatumLabels(datums);
    for (TDF_LabelSequence::Iterator it(datums); it.More(); it.Next()) {
      Handle(XCAFDoc_Datum) attr;
      if (!it.Value().FindAttribute(XCAFDoc_Datum::GetID(), attr) || attr.IsNull()) continue;
      Handle(XCAFDimTolObjects_DatumObject) obj = attr->GetObject();
      if (obj.IsNull()) continue;
      PmiItem item;
      item.kind = PmiKind::Datum;
      item.text = Ascii(obj->GetName());
      item.segStart = static_cast<int32_t>(out.segments.size() / 6);
      Bnd_Box box;
      AppendPresentation(obj->GetPresentation(), linDefl, out, box);
      FinishItem(item, box, obj->HasPointText(), obj->GetPointTextAttach(), out);
    }
  } catch (const Standard_Failure&) {
    // PMI okunamazsa model yine de gösterilir
  }
}

}  // namespace stepviewer
