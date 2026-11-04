#include "dxf_export.h"

#include <BRepAdaptor_Curve.hxx>
#include <BRepAdaptor_Surface.hxx>
#include <BRepTools.hxx>
#include <BRep_Tool.hxx>
#include <GCPnts_QuasiUniformDeflection.hxx>
#include <Standard_Failure.hxx>
#include <TopExp_Explorer.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Wire.hxx>
#include <gp_Circ.hxx>
#include <gp_Pln.hxx>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

namespace stepviewer {
namespace {

constexpr double kPi = 3.14159265358979323846;

struct Entity {
  enum Kind { Line, Circle, Arc } kind = Line;
  bool inner = false;
  double x1 = 0, y1 = 0, x2 = 0, y2 = 0;  // doğru uçları veya merkez (x1, y1)
  double r = 0, a1 = 0, a2 = 0;           // yarıçap, başlangıç/bitiş açısı (derece, saat yönü tersi)
};

struct Frame {
  gp_Pnt o;
  gp_Vec x, y;
  void Map(const gp_Pnt& p, double& u, double& v) const {
    const gp_Vec d(o, p);
    u = d.Dot(x);
    v = d.Dot(y);
  }
};

double AngleDeg(double cx, double cy, double px, double py) {
  double a = std::atan2(py - cy, px - cx) * 180.0 / kPi;
  if (a < 0) a += 360.0;
  return a;
}

void AddEdge(const TopoDS_Edge& edge, bool inner, const Frame& fr, const gp_Vec& normal,
             std::vector<Entity>& out) {
  if (BRep_Tool::Degenerated(edge)) return;
  BRepAdaptor_Curve c(edge);
  const double f = c.FirstParameter(), l = c.LastParameter();
  if (c.GetType() == GeomAbs_Line) {
    Entity e;
    e.kind = Entity::Line;
    e.inner = inner;
    fr.Map(c.Value(f), e.x1, e.y1);
    fr.Map(c.Value(l), e.x2, e.y2);
    out.push_back(e);
    return;
  }
  if (c.GetType() == GeomAbs_Circle) {
    const gp_Circ circ = c.Circle();
    const double axisDot = gp_Vec(circ.Axis().Direction()).Dot(normal);
    if (std::abs(std::abs(axisDot) - 1.0) < 1e-6) {
      Entity e;
      e.inner = inner;
      e.r = circ.Radius();
      fr.Map(circ.Location(), e.x1, e.y1);
      if (std::abs((l - f) - 2 * kPi) < 1e-9) {
        e.kind = Entity::Circle;
      } else {
        e.kind = Entity::Arc;
        double px, py, qx, qy;
        fr.Map(c.Value(f), px, py);
        fr.Map(c.Value(l), qx, qy);
        const double s = AngleDeg(e.x1, e.y1, px, py), t = AngleDeg(e.x1, e.y1, qx, qy);
        // DXF yayları saat yönü tersine gider
        if (axisDot > 0) {
          e.a1 = s;
          e.a2 = t;
        } else {
          e.a1 = t;
          e.a2 = s;
        }
      }
      out.push_back(e);
      return;
    }
  }
  // Diğer eğriler (spline, elips): kısa doğru parçalarına böl
  std::vector<gp_Pnt> pts;
  GCPnts_QuasiUniformDeflection disc(c, 0.01, f, l);
  if (disc.IsDone() && disc.NbPoints() >= 2) {
    for (int i = 1; i <= disc.NbPoints(); ++i) pts.push_back(disc.Value(i));
  } else {
    for (int i = 0; i <= 64; ++i) pts.push_back(c.Value(f + (l - f) * i / 64.0));
  }
  for (size_t i = 1; i < pts.size(); ++i) {
    Entity e;
    e.kind = Entity::Line;
    e.inner = inner;
    fr.Map(pts[i - 1], e.x1, e.y1);
    fr.Map(pts[i], e.x2, e.y2);
    out.push_back(e);
  }
}

void Extend(double x, double y, double* b) {
  b[0] = std::min(b[0], x);
  b[1] = std::min(b[1], y);
  b[2] = std::max(b[2], x);
  b[3] = std::max(b[3], y);
}

bool AngleInArc(double a, double a1, double a2) {
  if (a1 <= a2) return a >= a1 && a <= a2;
  return a >= a1 || a <= a2;
}

void Num(std::string& s, int code, double v) {
  char buf[64];
  std::snprintf(buf, sizeof(buf), "%d\n%.6f\n", code, std::abs(v) < 5e-7 ? 0.0 : v);
  s += buf;
}

void Str(std::string& s, int code, const char* v) {
  char buf[64];
  std::snprintf(buf, sizeof(buf), "%d\n%s\n", code, v);
  s += buf;
}

}  // namespace

bool ExportFaceDxf(const TopoDS_Face& face, DxfResult& out) {
  try {
    BRepAdaptor_Surface s(face, true);
    if (s.GetType() != GeomAbs_Plane) return false;
    const double um = 0.5 * (s.FirstUParameter() + s.LastUParameter());
    const double vm = 0.5 * (s.FirstVParameter() + s.LastVParameter());
    gp_Pnt p;
    gp_Vec du, dv;
    s.D1(um, vm, p, du, dv);
    gp_Vec n = du.Crossed(dv);
    if (n.Magnitude() < 1e-12) return false;
    n.Normalize();
    if (face.Orientation() == TopAbs_REVERSED) n.Reverse();

    // Dışarıdan bakan sağ elli çerçeve: X düzlemin X'i, Y = n x X
    Frame fr;
    fr.o = s.Plane().Location();
    fr.x = gp_Vec(s.Plane().Position().XDirection());
    fr.y = n.Crossed(fr.x);

    std::vector<Entity> ents;
    const TopoDS_Wire outer = BRepTools::OuterWire(face);
    int inner = 0;
    for (TopExp_Explorer wx(face, TopAbs_WIRE); wx.More(); wx.Next()) {
      const TopoDS_Wire w = TopoDS::Wire(wx.Current());
      const bool isInner = !outer.IsNull() && !w.IsSame(outer);
      if (isInner) ++inner;
      for (TopExp_Explorer ex(w, TopAbs_EDGE); ex.More(); ex.Next()) {
        AddEdge(TopoDS::Edge(ex.Current()), isInner, fr, n, ents);
      }
    }
    if (ents.empty()) return false;

    double b[4] = {1e300, 1e300, -1e300, -1e300};
    for (const Entity& e : ents) {
      if (e.kind == Entity::Line) {
        Extend(e.x1, e.y1, b);
        Extend(e.x2, e.y2, b);
      } else if (e.kind == Entity::Circle) {
        Extend(e.x1 - e.r, e.y1 - e.r, b);
        Extend(e.x1 + e.r, e.y1 + e.r, b);
      } else {
        const double rad1 = e.a1 * kPi / 180.0, rad2 = e.a2 * kPi / 180.0;
        Extend(e.x1 + e.r * std::cos(rad1), e.y1 + e.r * std::sin(rad1), b);
        Extend(e.x1 + e.r * std::cos(rad2), e.y1 + e.r * std::sin(rad2), b);
        for (int q = 0; q < 4; ++q) {
          if (AngleInArc(q * 90.0, e.a1, e.a2)) {
            Extend(e.x1 + e.r * std::cos(q * kPi / 2), e.y1 + e.r * std::sin(q * kPi / 2), b);
          }
        }
      }
    }
    const double ox = b[0], oy = b[1];
    out.width = b[2] - b[0];
    out.height = b[3] - b[1];
    out.innerLoops = inner;

    std::string& t = out.text;
    t.clear();
    t.reserve(ents.size() * 120 + 512);
    Str(t, 999, "Cepte CAD - units: mm");
    Str(t, 0, "SECTION");
    Str(t, 2, "HEADER");
    Str(t, 9, "$ACADVER");
    Str(t, 1, "AC1009");
    Str(t, 9, "$EXTMIN");
    Num(t, 10, 0);
    Num(t, 20, 0);
    Num(t, 30, 0);
    Str(t, 9, "$EXTMAX");
    Num(t, 10, out.width);
    Num(t, 20, out.height);
    Num(t, 30, 0);
    Str(t, 0, "ENDSEC");
    Str(t, 0, "SECTION");
    Str(t, 2, "ENTITIES");
    for (const Entity& e : ents) {
      const char* layer = e.inner ? "INNER" : "OUTER";
      if (e.kind == Entity::Line) {
        Str(t, 0, "LINE");
        Str(t, 8, layer);
        Num(t, 10, e.x1 - ox);
        Num(t, 20, e.y1 - oy);
        Num(t, 30, 0);
        Num(t, 11, e.x2 - ox);
        Num(t, 21, e.y2 - oy);
        Num(t, 31, 0);
      } else if (e.kind == Entity::Circle) {
        Str(t, 0, "CIRCLE");
        Str(t, 8, layer);
        Num(t, 10, e.x1 - ox);
        Num(t, 20, e.y1 - oy);
        Num(t, 30, 0);
        Num(t, 40, e.r);
      } else {
        Str(t, 0, "ARC");
        Str(t, 8, layer);
        Num(t, 10, e.x1 - ox);
        Num(t, 20, e.y1 - oy);
        Num(t, 30, 0);
        Num(t, 40, e.r);
        Num(t, 50, e.a1);
        Num(t, 51, e.a2);
      }
    }
    Str(t, 0, "ENDSEC");
    Str(t, 0, "EOF");
    return true;
  } catch (const Standard_Failure&) {
    return false;
  }
}

}  // namespace stepviewer
