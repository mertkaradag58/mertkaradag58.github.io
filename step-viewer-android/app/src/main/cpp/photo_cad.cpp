#include "photo_cad.h"

#include <BRepAlgoAPI_Common.hxx>
#include <BRepBndLib.hxx>
#include <BRepBuilderAPI_MakeEdge.hxx>
#include <BRepBuilderAPI_MakeFace.hxx>
#include <BRepBuilderAPI_MakePolygon.hxx>
#include <BRepBuilderAPI_MakeWire.hxx>
#include <BRepCheck_Analyzer.hxx>
#include <BRepGProp.hxx>
#include <BRepPrimAPI_MakePrism.hxx>
#include <BRepPrimAPI_MakeRevol.hxx>
#include <BRep_Builder.hxx>
#include <Bnd_Box.hxx>
#include <GProp_GProps.hxx>
#include <BRepTools.hxx>
#include <IFSelect_ReturnStatus.hxx>
#include <IGESControl_Writer.hxx>
#include <Interface_Static.hxx>
#include <STEPCAFControl_Writer.hxx>
#include <STEPControl_StepModelType.hxx>
#include <ShapeFix_Shape.hxx>
#include <ShapeUpgrade_UnifySameDomain.hxx>
#include <Standard_Failure.hxx>
#include <TDataStd_Name.hxx>
#include <TDocStd_Document.hxx>
#include <TopExp_Explorer.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Compound.hxx>
#include <TopoDS_Iterator.hxx>
#include <XCAFDoc_DocumentTool.hxx>
#include <XCAFDoc_ShapeTool.hxx>
#include <gp_Ax3.hxx>
#include <gp_Circ.hxx>
#include <gp_Pln.hxx>

#include <algorithm>
#include <cmath>
#include <functional>
#include <limits>

namespace stepviewer {

bool DecodeSketch(const double* d, size_t n, Sketch& out) {
  out.clear();
  if (n < 1) return false;
  size_t i = 0;
  const int loops = static_cast<int>(d[i++]);
  for (int l = 0; l < loops; ++l) {
    if (i >= n) return false;
    SketchLoop loop;
    const int type = static_cast<int>(d[i++]);
    if (type == 1) {
      if (i + 3 > n) return false;
      loop.circle = true;
      loop.cx = d[i];
      loop.cy = d[i + 1];
      loop.r = d[i + 2];
      i += 3;
    } else {
      if (i >= n) return false;
      const size_t pc = static_cast<size_t>(d[i++]);
      if (i + 2 * pc > n) return false;
      loop.pts.assign(d + i, d + i + 2 * pc);
      i += 2 * pc;
    }
    out.push_back(std::move(loop));
  }
  return !out.empty();
}

namespace {

// 2B taslağın yerleştirileceği düzlem: (u, v) -> origin + u*U + v*V
struct Placement {
  gp_Pnt origin;
  gp_Dir u, v;
  gp_Pnt Map(double a, double b) const {
    return gp_Pnt(origin.X() + a * u.X() + b * v.X(), origin.Y() + a * u.Y() + b * v.Y(),
                  origin.Z() + a * u.Z() + b * v.Z());
  }
  gp_Dir Normal() const { return u.Crossed(v); }
  gp_Ax3 Axes() const { return gp_Ax3(origin, Normal(), u); }
};

struct Box2 {
  double minX = std::numeric_limits<double>::max(), minY = minX;
  double maxX = -std::numeric_limits<double>::max(), maxY = maxX;
  void Add(double x, double y) {
    minX = std::min(minX, x);
    maxX = std::max(maxX, x);
    minY = std::min(minY, y);
    maxY = std::max(maxY, y);
  }
  bool Valid() const { return maxX > minX && maxY > minY; }
};

Box2 OuterBox(const Sketch& s) {
  Box2 b;
  if (s.empty()) return b;
  const SketchLoop& o = s.front();
  if (o.circle) {
    b.Add(o.cx - o.r, o.cy - o.r);
    b.Add(o.cx + o.r, o.cy + o.r);
  } else {
    for (size_t i = 0; i + 1 < o.pts.size(); i += 2) b.Add(o.pts[i], o.pts[i + 1]);
  }
  return b;
}

// Taslağı dış kutusunun sol alt köşesi (0, 0) olacak şekilde kaydırır.
Sketch Normalized(const Sketch& s) {
  const Box2 b = OuterBox(s);
  Sketch r = s;
  for (SketchLoop& l : r) {
    if (l.circle) {
      l.cx -= b.minX;
      l.cy -= b.minY;
    } else {
      for (size_t i = 0; i + 1 < l.pts.size(); i += 2) {
        l.pts[i] -= b.minX;
        l.pts[i + 1] -= b.minY;
      }
    }
  }
  return r;
}

double SignedArea(const std::vector<double>& p) {
  const size_t n = p.size() / 2;
  double a = 0;
  for (size_t i = 0; i < n; ++i) {
    const size_t j = (i + 1) % n;
    a += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1];
  }
  return a / 2;
}

// Ardışık çakışan noktaları atar; ccw istenen yöne çevirir.
std::vector<double> CleanPolygon(const std::vector<double>& in, bool ccw) {
  std::vector<double> p;
  const double eps = 1e-4;
  for (size_t i = 0; i + 1 < in.size(); i += 2) {
    if (!p.empty() && std::hypot(in[i] - p[p.size() - 2], in[i + 1] - p.back()) < eps) continue;
    p.push_back(in[i]);
    p.push_back(in[i + 1]);
  }
  while (p.size() >= 4 && std::hypot(p[0] - p[p.size() - 2], p[1] - p.back()) < eps) {
    p.pop_back();
    p.pop_back();
  }
  if (p.size() < 6) return {};
  if ((SignedArea(p) > 0) != ccw) {
    std::vector<double> r;
    for (size_t i = p.size(); i >= 2; i -= 2) {
      r.push_back(p[i - 2]);
      r.push_back(p[i - 1]);
    }
    p.swap(r);
  }
  return p;
}

// Bitişik olmayan kenarlar kesişiyor mu (kendini kesen kontur)?
bool SelfIntersects(const std::vector<double>& p) {
  const size_t n = p.size() / 2;
  auto cross = [](double ax, double ay, double bx, double by, double cx, double cy) {
    return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
  };
  for (size_t i = 0; i < n; ++i) {
    const size_t i2 = (i + 1) % n;
    for (size_t j = i + 1; j < n; ++j) {
      const size_t j2 = (j + 1) % n;
      if (j == i2 || j2 == i) continue;
      const double d1 = cross(p[2 * i], p[2 * i + 1], p[2 * i2], p[2 * i2 + 1], p[2 * j], p[2 * j + 1]);
      const double d2 = cross(p[2 * i], p[2 * i + 1], p[2 * i2], p[2 * i2 + 1], p[2 * j2], p[2 * j2 + 1]);
      const double d3 = cross(p[2 * j], p[2 * j + 1], p[2 * j2], p[2 * j2 + 1], p[2 * i], p[2 * i + 1]);
      const double d4 = cross(p[2 * j], p[2 * j + 1], p[2 * j2], p[2 * j2 + 1], p[2 * i2], p[2 * i2 + 1]);
      if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0)))
        return true;
    }
  }
  return false;
}

bool MakeLoopWire(const SketchLoop& l, const Placement& pl, bool outer, TopoDS_Wire& wire) {
  if (l.circle) {
    if (l.r <= 1e-3) return false;
    // Dış döngü düzlem normaline göre saat yönü tersi, delik saat yönü.
    gp_Circ c(gp_Ax2(pl.Map(l.cx, l.cy), pl.Normal(), pl.u), l.r);
    TopoDS_Edge e = BRepBuilderAPI_MakeEdge(c);
    BRepBuilderAPI_MakeWire mw(e);
    if (!mw.IsDone()) return false;
    wire = mw.Wire();
    if (!outer) wire.Reverse();
    return true;
  }
  const std::vector<double> p = CleanPolygon(l.pts, outer);
  if (p.empty() || SelfIntersects(p)) return false;
  BRepBuilderAPI_MakePolygon poly;
  for (size_t i = 0; i + 1 < p.size(); i += 2) poly.Add(pl.Map(p[i], p[i + 1]));
  poly.Close();
  if (!poly.IsDone()) return false;
  wire = poly.Wire();
  return true;
}

// Taslaktan düzlemsel yüz. Delikler dış konturun içinde varsayılır.
bool MakeSketchFace(const Sketch& s, const Placement& pl, bool withHoles, TopoDS_Face& face,
                    std::string& error) {
  TopoDS_Wire outer;
  if (s.empty() || !MakeLoopWire(s.front(), pl, true, outer)) {
    error = (!s.empty() && !s.front().circle && s.front().pts.size() >= 6) ? "bad_profile" : "open_profile";
    return false;
  }
  BRepBuilderAPI_MakeFace mf(gp_Pln(pl.Axes()), outer);
  if (!mf.IsDone()) {
    error = "bad_profile";
    return false;
  }
  if (withHoles) {
    for (size_t i = 1; i < s.size(); ++i) {
      TopoDS_Wire w;
      if (MakeLoopWire(s[i], pl, false, w)) mf.Add(w);
    }
  }
  face = mf.Face();
  if (!BRepCheck_Analyzer(face).IsValid()) {
    ShapeFix_Shape fix(face);
    fix.Perform();
    TopExp_Explorer ex(fix.Shape(), TopAbs_FACE);
    if (!ex.More()) {
      error = "bad_profile";
      return false;
    }
    face = TopoDS::Face(ex.Current());
    if (!BRepCheck_Analyzer(face).IsValid()) {
      error = "bad_profile";  // kendini kesen kontur
      return false;
    }
  }
  return true;
}

TopoDS_Shape Prism(const TopoDS_Face& f, const gp_Vec& v) {
  BRepPrimAPI_MakePrism mp(f, v);
  mp.Build();
  return mp.IsDone() ? mp.Shape() : TopoDS_Shape();
}

bool WriteStep(const TopoDS_Shape& shape, const std::string& path, const std::string& name) {
  Handle(TDocStd_Document) doc = new TDocStd_Document("BinXCAF");
  Handle(XCAFDoc_ShapeTool) tool = XCAFDoc_DocumentTool::ShapeTool(doc->Main());
  TDF_Label label = tool->AddShape(shape, false);
  TDataStd_Name::Set(label, TCollection_ExtendedString(name.c_str(), true));
  Interface_Static::SetCVal("write.step.unit", "MM");
  Interface_Static::SetIVal("write.step.schema", 4);  // AP214
  STEPCAFControl_Writer w;
  if (!w.Transfer(doc, STEPControl_AsIs)) return false;
  return w.Write(path.c_str()) == IFSelect_RetDone;
}

}  // namespace

PhotoCadResult BuildPhotoSolid(PhotoCadMode mode, const Sketch& aIn, const Sketch& bIn,
                               double param, int axis, const std::string& outPath,
                               const std::string& name, const Sketch& cIn, int flags) {
  PhotoCadResult res;
  try {
    const Sketch a = Normalized(aIn);
    const Box2 ba = OuterBox(a);
    if (!ba.Valid()) {
      res.error = "open_profile";
      return res;
    }
    TopoDS_Shape shape;
    std::string err;

    if (mode == PhotoCadMode::Extrude) {
      if (param <= 0) {
        res.error = "bad_param";
        return res;
      }
      Placement top{gp_Pnt(0, 0, 0), gp::DX(), gp::DY()};
      TopoDS_Face f;
      if (!MakeSketchFace(a, top, true, f, err)) {
        res.error = err;
        return res;
      }
      shape = Prism(f, gp_Vec(0, 0, param));
    } else if (mode == PhotoCadMode::TwoView) {
      const Sketch b = Normalized(bIn);
      const Box2 bb = OuterBox(b);
      if (!bb.Valid()) {
        res.error = "open_profile";
        return res;
      }
      // Üst görünüş: x sağa, resimde yukarı = arka (+y); kFlipTop ile resimde yukarı = ön.
      // Ön görünüş: x sağa, resimde yukarı = z. Yan görünüş: soldan bakışta resimde sağ = ön (-y).
      const Sketch c = cIn.empty() ? Sketch() : Normalized(cIn);
      const Box2 bc = OuterBox(c);
      const bool hasSide = bc.Valid();
      const double depthTop = ba.maxY - ba.minY, height = bb.maxY - bb.minY;
      const double depth = hasSide ? std::max(depthTop, bc.maxX - bc.minX) : depthTop;
      const double width = std::max(ba.maxX - ba.minX, bb.maxX - bb.minX);
      Placement top = (flags & kFlipTop) ? Placement{gp_Pnt(0, depthTop, -1), gp::DX(), -gp::DY()}
                                         : Placement{gp_Pnt(0, 0, -1), gp::DX(), gp::DY()};
      Placement front{gp_Pnt(0, -1, 0), gp::DX(), gp::DZ()};
      TopoDS_Face fa, fb;
      if (!MakeSketchFace(a, top, true, fa, err) || !MakeSketchFace(b, front, true, fb, err)) {
        res.error = err;
        return res;
      }
      TopoDS_Shape pa = Prism(fa, gp_Vec(0, 0, height + 2));
      TopoDS_Shape pb = Prism(fb, gp_Vec(0, depth + 2, 0));
      if (pa.IsNull() || pb.IsNull()) {
        res.error = "build_failed";
        return res;
      }
      if (hasSide) {
        const double ds = bc.maxX - bc.minX;
        Placement side = (flags & kSideRight) ? Placement{gp_Pnt(-1, 0, 0), gp::DY(), gp::DZ()}
                                              : Placement{gp_Pnt(-1, ds, 0), -gp::DY(), gp::DZ()};
        TopoDS_Face fc;
        if (!MakeSketchFace(c, side, true, fc, err)) {
          res.error = err;
          return res;
        }
        TopoDS_Shape pc = Prism(fc, gp_Vec(width + 2, 0, 0));
        if (pc.IsNull()) {
          res.error = "build_failed";
          return res;
        }
        BRepAlgoAPI_Common c1(pa, pc);
        if (!c1.IsDone()) {
          res.error = "build_failed";
          return res;
        }
        pa = c1.Shape();
      }
      BRepAlgoAPI_Common common(pa, pb);
      if (!common.IsDone()) {
        res.error = "build_failed";
        return res;
      }
      shape = common.Shape();
    } else {
      // Döndürme: yan görünüş XZ düzleminde; yatay eksen = X yönü, dikey eksen = Z yönü.
      Placement side{gp_Pnt(0, 0, 0), gp::DX(), gp::DZ()};
      TopoDS_Face f;
      if (!MakeSketchFace(a, side, false, f, err)) {
        res.error = err;
        return res;
      }
      const double cu = (ba.minX + ba.maxX) / 2, cv = (ba.minY + ba.maxY) / 2;
      const double m = 1.0;
      Sketch half(1);
      if (axis == 0) {
        half[0].pts = {ba.minX - m, cv, ba.maxX + m, cv, ba.maxX + m, ba.maxY + m,
                       ba.minX - m, ba.maxY + m};
      } else {
        half[0].pts = {cu, ba.minY - m, ba.maxX + m, ba.minY - m, ba.maxX + m, ba.maxY + m,
                       cu, ba.maxY + m};
      }
      TopoDS_Face hf;
      if (!MakeSketchFace(half, side, false, hf, err)) {
        res.error = "build_failed";
        return res;
      }
      BRepAlgoAPI_Common common(f, hf);
      if (!common.IsDone()) {
        res.error = "build_failed";
        return res;
      }
      const gp_Ax1 ax = axis == 0 ? gp_Ax1(gp_Pnt(0, 0, cv), gp::DX())
                                  : gp_Ax1(gp_Pnt(cu, 0, 0), gp::DZ());
      BRep_Builder builder;
      TopoDS_Compound comp;
      builder.MakeCompound(comp);
      int solids = 0;
      for (TopExp_Explorer ex(common.Shape(), TopAbs_FACE); ex.More(); ex.Next()) {
        BRepPrimAPI_MakeRevol rev(ex.Current(), ax, 2 * M_PI);
        rev.Build();
        if (!rev.IsDone()) continue;
        builder.Add(comp, rev.Shape());
        ++solids;
      }
      if (solids == 0) {
        res.error = "build_failed";
        return res;
      }
      shape = solids == 1 ? TopoDS_Shape(TopoDS_Iterator(comp).Value()) : TopoDS_Shape(comp);
    }

    if (shape.IsNull()) {
      res.error = "build_failed";
      return res;
    }
    // Çokgen kenarlardan oluşan eş düzlemli yüzleri birleştir (daha temiz STEP).
    try {
      ShapeUpgrade_UnifySameDomain unify(shape, true, true, true);
      unify.Build();
      if (!unify.Shape().IsNull() && BRepCheck_Analyzer(unify.Shape()).IsValid())
        shape = unify.Shape();
    } catch (const Standard_Failure&) {
    }

    GProp_GProps props;
    BRepGProp::VolumeProperties(shape, props);
    res.volume = std::fabs(props.Mass());
    if (res.volume < 1e-6) {
      res.error = "empty_result";
      return res;
    }
    Bnd_Box box;
    BRepBndLib::Add(shape, box);
    if (!box.IsVoid()) {
      double x0, y0, z0, x1, y1, z1;
      box.Get(x0, y0, z0, x1, y1, z1);
      res.size[0] = x1 - x0;
      res.size[1] = y1 - y0;
      res.size[2] = z1 - z0;
    }
    if (!WriteStep(shape, outPath, name)) {
      res.error = "write_failed";
      return res;
    }
    // Aynı adla IGES ve BREP kopyaları (kaydetme seçenekleri için); hata olursa yok sayılır.
    const size_t dot = outPath.find_last_of('.');
    const std::string base = dot == std::string::npos ? outPath : outPath.substr(0, dot);
    try {
      IGESControl_Writer iges("MM", 1);  // 1 = BRep (katı) modu
      if (iges.AddShape(shape)) {
        iges.ComputeModel();
        iges.Write((base + ".igs").c_str());
      }
      BRepTools::Write(shape, (base + ".brep").c_str());
    } catch (const Standard_Failure&) {
    }
    res.ok = true;
  } catch (const Standard_Failure& e) {
    res.error = std::string("build_failed");
  } catch (const std::exception&) {
    res.error = "build_failed";
  }
  return res;
}

}  // namespace stepviewer
