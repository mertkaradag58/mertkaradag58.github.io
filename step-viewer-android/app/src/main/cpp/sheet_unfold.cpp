#include "sheet_unfold.h"

#include <BRepAdaptor_Curve.hxx>
#include <BRepAdaptor_Surface.hxx>
#include <BRepClass_FaceClassifier.hxx>
#include <BRepGProp.hxx>
#include <BRepLProp_SLProps.hxx>
#include <BRepTools.hxx>
#include <BRep_Tool.hxx>
#include <ElSLib.hxx>
#include <GCPnts_QuasiUniformDeflection.hxx>
#include <GProp_GProps.hxx>
#include <Geom2d_Curve.hxx>
#include <IntCurvesFace_ShapeIntersector.hxx>
#include <Standard_Failure.hxx>
#include <TopExp.hxx>
#include <TopExp_Explorer.hxx>
#include <TopTools_IndexedDataMapOfShapeListOfShape.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <TopTools_ListIteratorOfListOfShape.hxx>
#include <TopoDS.hxx>
#include <gp_Ax3.hxx>
#include <gp_Circ.hxx>
#include <gp_Cylinder.hxx>
#include <gp_Lin.hxx>
#include <gp_Pln.hxx>
#include <gp_Trsf.hxx>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <deque>

namespace stepviewer {
namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kTangentCos = 0.9986;  // ~3°

double Wrap(double u, double center) {
  const double tp = 2 * kPi;
  return u + tp * std::round((center - u) / tp);
}

// Bir yüzün açınıma eşlemesi. Düzlem: katı dönüşüm T (3B -> taban düzlemi). Silindir: yüzey
// noktası önce ebeveyn düzlemine açılır (teğet çizgisinden nötr yay boyu kadar), sonra T.
struct FaceMap {
  bool mapped = false;
  bool cyl = false;
  gp_Trsf T;
  gp_Cylinder c;
  double uRef = 0, sign = 1, rn = 0, uCenter = 0;
  int bend = -1;
};

gp_Vec UnitDU(const gp_Cylinder& c, double u) {
  gp_Pnt p;
  gp_Vec du, dv;
  ElSLib::D1(u, 0.0, c, p, du, dv);
  return du.Normalized();
}

gp_Pnt Flat3D(const FaceMap& m, const gp_Pnt& p) {
  if (!m.cyl) return p.Transformed(m.T);
  double u, v;
  ElSLib::Parameters(m.c, p, u, v);
  u = Wrap(u, m.uCenter);
  const double d = m.sign * (u - m.uRef);
  gp_Pnt q = ElSLib::Value(m.uRef, v, m.c);
  q.Translate(UnitDU(m.c, m.uRef) * (m.sign * d * m.rn));
  return q.Transformed(m.T);
}

struct Frame2 {
  gp_Pnt o;
  gp_Vec x, y;
  void Map(const gp_Pnt& p, double& u, double& v) const {
    const gp_Vec d(o, p);
    u = d.Dot(x);
    v = d.Dot(y);
  }
};

// Yüzün bir noktadaki dış normali (yüz yönü dahil). uv yüzey parametreleri.
bool NormalUV(const TopoDS_Face& f, double u, double v, gp_Pnt& p, gp_Vec& n) {
  BRepAdaptor_Surface s(f, true);
  BRepLProp_SLProps props(s, u, v, 1, 1e-7);
  if (!props.IsNormalDefined()) return false;
  p = props.Value();
  n = gp_Vec(props.Normal());
  if (f.Orientation() == TopAbs_REVERSED) n.Reverse();
  return true;
}

// Kenarın ortasında yüzün normali ve nokta.
bool NormalOnEdge(const TopoDS_Edge& e, const TopoDS_Face& f, gp_Pnt& p, gp_Vec& n) {
  double first, last;
  Handle(Geom2d_Curve) pc = BRep_Tool::CurveOnSurface(e, f, first, last);
  if (pc.IsNull()) return false;
  const gp_Pnt2d uv = pc->Value(0.5 * (first + last));
  return NormalUV(f, uv.X(), uv.Y(), p, n);
}

struct Ent {
  enum Kind { Line, Circle, Arc } kind = Line;
  double x1 = 0, y1 = 0, x2 = 0, y2 = 0;  // doğru uçları veya merkez
  double r = 0, a1 = 0, a2 = 0;           // derece, saat yönü tersi
};

double AngleDeg(double cx, double cy, double px, double py) {
  double a = std::atan2(py - cy, px - cx) * 180.0 / kPi;
  if (a < 0) a += 360.0;
  return a;
}

bool AngleInArc(double a, double a1, double a2) {
  if (a1 <= a2) return a >= a1 && a <= a2;
  return a >= a1 || a <= a2;
}

// Douglas-Peucker: eşdoğrusal noktaları at (yay yüzündeki kesit eğrileri düz çizgiye iner).
void Simplify(const std::vector<std::pair<double, double>>& in, size_t a, size_t b, double tol,
              std::vector<char>& keep) {
  if (b <= a + 1) return;
  const double ax = in[a].first, ay = in[a].second, bx = in[b].first, by = in[b].second;
  const double dx = bx - ax, dy = by - ay;
  const double len = std::hypot(dx, dy);
  double best = -1;
  size_t bi = a;
  for (size_t i = a + 1; i < b; ++i) {
    const double px = in[i].first - ax, py = in[i].second - ay;
    const double d = len > 1e-12 ? std::abs(px * dy - py * dx) / len : std::hypot(px, py);
    if (d > best) {
      best = d;
      bi = i;
    }
  }
  if (best > tol) {
    keep[bi] = 1;
    Simplify(in, a, bi, tol, keep);
    Simplify(in, bi, b, tol, keep);
  }
}

void Num(std::string& s, int code, double v) {
  char buf[64];
  std::snprintf(buf, sizeof(buf), "%d\n%.6f\n", code, std::abs(v) < 5e-7 ? 0.0 : v);
  s += buf;
}

void Str(std::string& s, int code, const char* v) {
  s += std::to_string(code);
  s += '\n';
  s += v;
  s += '\n';
}

}  // namespace

void UnfoldSheet(const TopoDS_Shape& part, const TopoDS_Face& baseFaceIn, double k, SheetResult& out) {
  out = SheetResult();
  out.kFactor = k;
  try {
    TopTools_IndexedMapOfShape faces;
    TopExp::MapShapes(part, TopAbs_FACE, faces);
    TopTools_IndexedDataMapOfShapeListOfShape e2f;
    TopExp::MapShapesAndAncestors(part, TopAbs_EDGE, TopAbs_FACE, e2f);
    const int nf = faces.Extent();
    if (nf == 0) return;

    // Taban yüzü
    int base = 0;
    if (!baseFaceIn.IsNull()) {
      base = faces.FindIndex(baseFaceIn);
      if (base == 0) return;
    } else {
      double best = -1;
      for (int i = 1; i <= nf; ++i) {
        const TopoDS_Face f = TopoDS::Face(faces(i));
        if (BRepAdaptor_Surface(f, false).GetType() != GeomAbs_Plane) continue;
        GProp_GProps g;
        BRepGProp::SurfaceProperties(f, g);
        if (g.Mass() > best) {
          best = g.Mass();
          base = i;
        }
      }
      if (base == 0) {
        out.status = SheetStatus::NotPlanar;
        return;
      }
    }
    const TopoDS_Face baseFace = TopoDS::Face(faces(base));
    BRepAdaptor_Surface bs(baseFace, true);
    if (bs.GetType() != GeomAbs_Plane) {
      out.status = SheetStatus::NotPlanar;
      return;
    }

    // Taban yüzün içinde bir nokta ve dış normal
    double umin, umax, vmin, vmax;
    BRepTools::UVBounds(baseFace, umin, umax, vmin, vmax);
    gp_Pnt inner;
    gp_Vec nBase;
    bool found = false;
    for (int ring = 0; ring <= 6 && !found; ++ring) {
      const int steps = ring == 0 ? 1 : 8 * ring;
      for (int s = 0; s < steps && !found; ++s) {
        const double ang = 2 * kPi * s / steps;
        const double u = 0.5 * (umin + umax) + 0.5 * (umax - umin) * ring / 7.0 * std::cos(ang);
        const double v = 0.5 * (vmin + vmax) + 0.5 * (vmax - vmin) * ring / 7.0 * std::sin(ang);
        BRepClass_FaceClassifier cls(baseFace, gp_Pnt2d(u, v), 1e-7);
        if (cls.State() != TopAbs_IN) continue;
        found = NormalUV(baseFace, u, v, inner, nBase);
      }
    }
    if (!found) return;
    nBase.Normalize();

    // Kalınlık: içeri doğru ışın, ilk karşı yüz
    IntCurvesFace_ShapeIntersector ray;
    ray.Load(part, 1e-7);
    ray.Perform(gp_Lin(inner, gp_Dir(-nBase)), -1e-9, 1e9);
    double t = 1e300;
    if (ray.IsDone()) {
      for (int i = 1; i <= ray.NbPnt(); ++i) {
        const double w = ray.WParameter(i);
        if (w > 1e-5 && w < t) t = w;
      }
    }
    if (t >= 1e299) {
      out.status = SheetStatus::NoThickness;
      return;
    }
    out.thickness = t;

    // Taban çerçevesi (dışarıdan bakılır, ayna yok)
    const gp_Pln pln = bs.Plane();
    Frame2 fr;
    fr.o = pln.Location();
    fr.x = gp_Vec(pln.Position().XDirection());
    fr.x = (fr.x - nBase * fr.x.Dot(nBase)).Normalized();
    fr.y = nBase.Crossed(fr.x);

    std::vector<FaceMap> maps(static_cast<size_t>(nf) + 1);
    std::vector<char> seen(static_cast<size_t>(nf) + 1, 0);
    struct BendAcc {
      gp_Cylinder c;
      double uRef = 0, sign = 1, rn = 0, uCenter = 0, rin = 0;
      bool up = true;
      gp_Trsf T;
      double dMax = 0, vMin = 1e300, vMax = -1e300;
    };
    std::vector<BendAcc> bends;

    maps[base].mapped = true;  // T = birim
    seen[base] = 1;
    std::deque<int> queue{base};
    while (!queue.empty()) {
      const int fi = queue.front();
      queue.pop_front();
      const TopoDS_Face f = TopoDS::Face(faces(fi));
      const FaceMap pm = maps[fi];
      for (TopExp_Explorer ex(f, TopAbs_EDGE); ex.More(); ex.Next()) {
        const TopoDS_Edge e = TopoDS::Edge(ex.Current());
        if (BRep_Tool::Degenerated(e) || !e2f.Contains(e)) continue;
        for (TopTools_ListIteratorOfListOfShape it(e2f.FindFromKey(e)); it.More(); it.Next()) {
          const int ni = faces.FindIndex(it.Value());
          if (ni == 0 || ni == fi || seen[ni]) continue;
          const TopoDS_Face g = TopoDS::Face(faces(ni));
          gp_Pnt p1, p2;
          gp_Vec n1, n2;
          if (!NormalOnEdge(e, f, p1, n1) || !NormalOnEdge(e, g, p2, n2)) continue;
          if (n1.Normalized().Dot(n2.Normalized()) < kTangentCos) continue;  // keskin köşe: kalınlık yüzü
          seen[ni] = 1;
          BRepAdaptor_Surface gs(g, true);
          FaceMap cm;
          if (gs.GetType() == GeomAbs_Plane) {
            if (!pm.cyl) {
              cm = pm;  // aynı düzlemde bölünmüş yüz
              cm.bend = -1;
            } else {
              // Bükümden sonraki flanş: silindirin bu taraftaki teğet çizgisi ebeveyn düzlemine
              double u2, v2;
              ElSLib::Parameters(pm.c, p1, u2, v2);
              u2 = Wrap(u2, pm.uCenter);
              const double d2 = pm.sign * (u2 - pm.uRef);
              const gp_Dir a = pm.c.Axis().Direction();
              const gp_Pnt oc = ElSLib::Value(u2, v2, pm.c);
              const gp_Vec tc = UnitDU(pm.c, u2) * pm.sign;
              gp_Pnt ot = ElSLib::Value(pm.uRef, v2, pm.c);
              const gp_Vec dt = UnitDU(pm.c, pm.uRef) * pm.sign;
              ot.Translate(dt * (d2 * pm.rn));
              const gp_Ax3 from(oc, gp_Dir(gp_Vec(a).Crossed(tc)), gp_Dir(tc));
              const gp_Ax3 to(ot, gp_Dir(gp_Vec(a).Crossed(dt)), gp_Dir(dt));
              gp_Trsf m;
              m.SetDisplacement(from, to);
              cm.T = pm.T.Multiplied(m);
              cm.cyl = false;
              cm.bend = -1;
            }
          } else if (gs.GetType() == GeomAbs_Cylinder) {
            const gp_Cylinder cy = gs.Cylinder();
            double gu0, gu1, gv0, gv1;
            BRepTools::UVBounds(g, gu0, gu1, gv0, gv1);
            if (!pm.cyl) {
              // Yeni büküm
              double u0, v0;
              ElSLib::Parameters(cy, p2, u0, v0);
              const double um = 0.5 * (gu0 + gu1);
              u0 = Wrap(u0, um);
              cm.cyl = true;
              cm.c = cy;
              cm.T = pm.T;
              cm.uRef = u0;
              cm.sign = std::abs(u0 - gu0) <= std::abs(u0 - gu1) ? 1.0 : -1.0;
              cm.uCenter = um;
              // İç bükey mi (normal eksene doğru): bu yüz bükümün iç tarafı
              gp_Pnt pmid;
              gp_Vec nmid;
              NormalUV(g, um, 0.5 * (gv0 + gv1), pmid, nmid);
              const gp_Pnt onAxis = ElSLib::Value(um, 0.5 * (gv0 + gv1), gp_Cylinder(cy.Position(), 0.0));
              const gp_Vec radial(onAxis, pmid);
              const bool concave = radial.Dot(nmid) < 0;
              const double R = cy.Radius();
              double rin = concave ? R : R - t;
              if (rin < 0) rin = 0;
              cm.rn = rin + k * t;
              // Delik / pencere ile bölünmüş aynı büküm: mevcut bükümü kullan
              int same = -1;
              for (size_t bi = 0; bi < bends.size() && same < 0; ++bi) {
                const BendAcc& o = bends[bi];
                const gp_Lin la(o.c.Axis()), lb(cy.Axis());
                if (!la.Direction().IsParallel(lb.Direction(), 1e-6) || la.Distance(lb.Location()) > 1e-5 ||
                    std::abs(o.c.Radius() - cy.Radius()) > 1e-5 || o.sign != cm.sign) {
                  continue;
                }
                double ou, ov;
                ElSLib::Parameters(o.c, p2, ou, ov);
                if (std::abs(Wrap(ou, o.uRef) - o.uRef) > 1e-5) continue;
                const gp_Pnt q1(1, 2, 3), q2(-7, 5, 11);
                if (q1.Transformed(o.T).Distance(q1.Transformed(cm.T)) > 1e-6 ||
                    q2.Transformed(o.T).Distance(q2.Transformed(cm.T)) > 1e-6) {
                  continue;
                }
                same = static_cast<int>(bi);
              }
              if (same >= 0) {
                const BendAcc& o = bends[static_cast<size_t>(same)];
                cm.c = o.c;
                cm.uRef = o.uRef;
                double uc, vc;
                ElSLib::Parameters(o.c, gs.Value(um, 0.5 * (gv0 + gv1)), uc, vc);
                cm.uCenter = Wrap(uc, o.uCenter);
                cm.rn = o.rn;
                cm.bend = same;
                cm.mapped = true;
                maps[ni] = cm;
                queue.push_back(ni);
                continue;
              }
              BendAcc b;
              b.c = cy;
              b.uRef = cm.uRef;
              b.sign = cm.sign;
              b.rn = cm.rn;
              b.uCenter = cm.uCenter;
              b.rin = rin;
              b.up = concave;
              b.T = cm.T;
              cm.bend = static_cast<int>(bends.size());
              bends.push_back(b);
            } else {
              // Aynı bükümün devamı (eş eksenli, aynı yarıçap) olmalı
              const gp_Lin axA(pm.c.Axis()), axB(cy.Axis());
              const bool coaxial = axA.Direction().IsParallel(axB.Direction(), 1e-6) &&
                                   axA.Distance(axB.Location()) < 1e-5 &&
                                   std::abs(cy.Radius() - pm.c.Radius()) < 1e-5;
              if (!coaxial) {
                ++out.skippedFaces;
                continue;
              }
              cm = pm;
              double ue, ve;
              ElSLib::Parameters(pm.c, p1, ue, ve);
              ue = Wrap(ue, pm.uCenter);
              const gp_Pnt mid = gs.Value(0.5 * (gu0 + gu1), 0.5 * (gv0 + gv1));
              double uc, vc;
              ElSLib::Parameters(pm.c, mid, uc, vc);
              cm.uCenter = Wrap(uc, ue);
            }
          } else {
            ++out.skippedFaces;
            continue;
          }
          cm.mapped = true;
          maps[ni] = cm;
          queue.push_back(ni);
        }
      }
    }

    auto flat2 = [&](const FaceMap& m, const gp_Pnt& p, double& x, double& y) {
      fr.Map(Flat3D(m, p), x, y);
    };

    // Büküm açısı ve boyu: bükümün tüm yüzlerinin köşelerinden
    for (int i = 1; i <= nf; ++i) {
      const FaceMap& m = maps[i];
      if (!m.mapped || !m.cyl || m.bend < 0) continue;
      BendAcc& b = bends[static_cast<size_t>(m.bend)];
      const TopoDS_Face f = TopoDS::Face(faces(i));
      BRepAdaptor_Surface s(f, true);
      double u0, u1, v0, v1;
      BRepTools::UVBounds(f, u0, u1, v0, v1);
      for (double uu : {u0, u1}) {
        for (double vv : {v0, v1}) {
          double u, v;
          ElSLib::Parameters(m.c, s.Value(uu, vv), u, v);
          u = Wrap(u, m.uCenter);
          b.dMax = std::max(b.dMax, m.sign * (u - m.uRef));
          b.vMin = std::min(b.vMin, v);
          b.vMax = std::max(b.vMax, v);
        }
      }
    }

    // Alan
    double area = 0;
    for (int i = 1; i <= nf; ++i) {
      const FaceMap& m = maps[i];
      if (!m.mapped) continue;
      GProp_GProps g;
      BRepGProp::SurfaceProperties(faces(i), g);
      area += m.cyl ? g.Mass() * m.rn / std::max(1e-9, m.c.Radius()) : g.Mass();
    }
    out.area = area;

    // Kesim çizgileri: yalnızca bir açılmış yüze ait kenarlar (diğer taraf kalınlık yüzü)
    std::vector<Ent> ents;
    for (int i = 1; i <= nf; ++i) {
      const FaceMap& m = maps[i];
      if (!m.mapped) continue;
      const TopoDS_Face f = TopoDS::Face(faces(i));
      TopTools_IndexedMapOfShape edges;
      TopExp::MapShapes(f, TopAbs_EDGE, edges);
      gp_Vec nf3;
      if (!m.cyl) nf3 = gp_Vec(BRepAdaptor_Surface(f, true).Plane().Axis().Direction());
      for (int ei = 1; ei <= edges.Extent(); ++ei) {
        const TopoDS_Edge e = TopoDS::Edge(edges(ei));
        if (BRep_Tool::Degenerated(e)) continue;
        int owners = 0;
        if (e2f.Contains(e)) {
          TopTools_IndexedMapOfShape uniq;
          for (TopTools_ListIteratorOfListOfShape it(e2f.FindFromKey(e)); it.More(); it.Next()) {
            uniq.Add(it.Value());
          }
          for (int q = 1; q <= uniq.Extent(); ++q) {
            const int idx = faces.FindIndex(uniq(q));
            if (idx > 0 && maps[idx].mapped) ++owners;
          }
        }
        if (owners >= 2) continue;
        BRepAdaptor_Curve c(e);
        const double a = c.FirstParameter(), b = c.LastParameter();
        if (!m.cyl && c.GetType() == GeomAbs_Line) {
          Ent en;
          flat2(m, c.Value(a), en.x1, en.y1);
          flat2(m, c.Value(b), en.x2, en.y2);
          ents.push_back(en);
          continue;
        }
        if (!m.cyl && c.GetType() == GeomAbs_Circle &&
            std::abs(std::abs(gp_Vec(c.Circle().Axis().Direction()).Dot(nf3)) - 1.0) < 1e-6) {
          Ent en;
          en.r = c.Circle().Radius();
          flat2(m, c.Circle().Location(), en.x1, en.y1);
          if (std::abs((b - a) - 2 * kPi) < 1e-9) {
            en.kind = Ent::Circle;
          } else {
            en.kind = Ent::Arc;
            double px, py, qx, qy, mx, my;
            flat2(m, c.Value(a), px, py);
            flat2(m, c.Value(b), qx, qy);
            flat2(m, c.Value(0.5 * (a + b)), mx, my);
            const double s = AngleDeg(en.x1, en.y1, px, py), q = AngleDeg(en.x1, en.y1, qx, qy);
            const double md = AngleDeg(en.x1, en.y1, mx, my);
            if (AngleInArc(md, s, q)) {
              en.a1 = s;
              en.a2 = q;
            } else {
              en.a1 = q;
              en.a2 = s;
            }
          }
          ents.push_back(en);
          continue;
        }
        std::vector<gp_Pnt> pts;
        GCPnts_QuasiUniformDeflection disc(c, 0.01, a, b);
        if (disc.IsDone() && disc.NbPoints() >= 2) {
          for (int q = 1; q <= disc.NbPoints(); ++q) pts.push_back(disc.Value(q));
        } else {
          for (int q = 0; q <= 64; ++q) pts.push_back(c.Value(a + (b - a) * q / 64.0));
        }
        std::vector<std::pair<double, double>> p2(pts.size());
        for (size_t q = 0; q < pts.size(); ++q) flat2(m, pts[q], p2[q].first, p2[q].second);
        std::vector<char> keep(p2.size(), 0);
        keep.front() = keep.back() = 1;
        Simplify(p2, 0, p2.size() - 1, 1e-4, keep);
        size_t prev = 0;
        for (size_t q = 1; q < p2.size(); ++q) {
          if (!keep[q]) continue;
          Ent en;
          en.x1 = p2[prev].first;
          en.y1 = p2[prev].second;
          en.x2 = p2[q].first;
          en.y2 = p2[q].second;
          if (std::hypot(en.x2 - en.x1, en.y2 - en.y1) > 1e-7) ents.push_back(en);
          prev = q;
        }
      }
    }
    if (ents.empty()) return;

    // Büküm çizgileri (nötr yayın ortası)
    for (const BendAcc& b : bends) {
      SheetBend sb;
      sb.angleDeg = b.dMax * 180.0 / kPi;
      sb.innerRadius = b.rin;
      sb.length = b.vMax - b.vMin;
      sb.allowance = b.dMax * b.rn;
      sb.up = b.up;
      FaceMap m;
      m.mapped = m.cyl = true;
      m.c = b.c;
      m.T = b.T;
      m.uRef = b.uRef;
      m.sign = b.sign;
      m.rn = b.rn;
      m.uCenter = b.uCenter;
      const double um = b.uRef + b.sign * b.dMax * 0.5;
      flat2(m, ElSLib::Value(um, b.vMin, b.c), sb.x1, sb.y1);
      flat2(m, ElSLib::Value(um, b.vMax, b.c), sb.x2, sb.y2);
      out.bends.push_back(sb);
    }

    // En uzun düz kenarı yatay yap (lazer tablası için düzgün yerleşim)
    double bestLen = 0, rot = 0;
    for (const Ent& e : ents) {
      if (e.kind != Ent::Line) continue;
      const double l = std::hypot(e.x2 - e.x1, e.y2 - e.y1);
      if (l > bestLen + 1e-9) {
        bestLen = l;
        rot = std::atan2(e.y2 - e.y1, e.x2 - e.x1);
      }
    }
    // Yön: -90..90 arasına getir
    while (rot > kPi / 2) rot -= kPi;
    while (rot < -kPi / 2) rot += kPi;
    const double cr = std::cos(-rot), sr = std::sin(-rot);
    auto R = [&](double& x, double& y) {
      const double nx = x * cr - y * sr, ny = x * sr + y * cr;
      x = nx;
      y = ny;
    };
    const double rotDeg = -rot * 180.0 / kPi;
    for (Ent& e : ents) {
      R(e.x1, e.y1);
      if (e.kind == Ent::Line) {
        R(e.x2, e.y2);
      } else if (e.kind == Ent::Arc) {
        e.a1 = std::fmod(e.a1 + rotDeg + 720.0, 360.0);
        e.a2 = std::fmod(e.a2 + rotDeg + 720.0, 360.0);
      }
    }
    for (SheetBend& b : out.bends) {
      R(b.x1, b.y1);
      R(b.x2, b.y2);
    }

    // Sınır kutusu ve (0, 0)'a taşıma
    double bb[4] = {1e300, 1e300, -1e300, -1e300};
    auto ext = [&](double x, double y) {
      bb[0] = std::min(bb[0], x);
      bb[1] = std::min(bb[1], y);
      bb[2] = std::max(bb[2], x);
      bb[3] = std::max(bb[3], y);
    };
    for (const Ent& e : ents) {
      if (e.kind == Ent::Line) {
        ext(e.x1, e.y1);
        ext(e.x2, e.y2);
      } else if (e.kind == Ent::Circle) {
        ext(e.x1 - e.r, e.y1 - e.r);
        ext(e.x1 + e.r, e.y1 + e.r);
      } else {
        for (double a : {e.a1, e.a2}) ext(e.x1 + e.r * std::cos(a * kPi / 180), e.y1 + e.r * std::sin(a * kPi / 180));
        for (int q = 0; q < 4; ++q) {
          if (AngleInArc(q * 90.0, e.a1, e.a2)) ext(e.x1 + e.r * std::cos(q * kPi / 2), e.y1 + e.r * std::sin(q * kPi / 2));
        }
      }
    }
    const double ox = bb[0], oy = bb[1];
    out.width = bb[2] - bb[0];
    out.height = bb[3] - bb[1];
    // Kalınlık açınımın kısa kenarına göre çok büyükse bu bir sac değil (blok, kalınlık yüzü...)
    if (out.thickness > 0.5 * std::min(out.width, out.height)) {
      out.status = SheetStatus::NoThickness;
      out.bends.clear();
      return;
    }
    for (Ent& e : ents) {
      e.x1 -= ox;
      e.y1 -= oy;
      if (e.kind == Ent::Line) {
        e.x2 -= ox;
        e.y2 -= oy;
      }
    }
    for (SheetBend& b : out.bends) {
      b.x1 -= ox;
      b.y1 -= oy;
      b.x2 -= ox;
      b.y2 -= oy;
    }

    // Önizleme çizgileri
    for (const Ent& e : ents) {
      if (e.kind == Ent::Line) {
        out.outline.insert(out.outline.end(), {float(e.x1), float(e.y1), float(e.x2), float(e.y2)});
        continue;
      }
      double a1 = 0, a2 = 360;
      if (e.kind == Ent::Arc) {
        a1 = e.a1;
        a2 = e.a2 < e.a1 ? e.a2 + 360 : e.a2;
      }
      const int n = std::max(4, static_cast<int>((a2 - a1) / 7.5));
      for (int q = 0; q < n; ++q) {
        const double s = (a1 + (a2 - a1) * q / n) * kPi / 180, u = (a1 + (a2 - a1) * (q + 1) / n) * kPi / 180;
        out.outline.insert(out.outline.end(), {float(e.x1 + e.r * std::cos(s)), float(e.y1 + e.r * std::sin(s)),
                                               float(e.x1 + e.r * std::cos(u)), float(e.y1 + e.r * std::sin(u))});
      }
    }
    for (const SheetBend& b : out.bends) {
      out.bendLines.insert(out.bendLines.end(), {float(b.x1), float(b.y1), float(b.x2), float(b.y2)});
    }

    // DXF (R12)
    std::string& d = out.dxf;
    d.reserve(ents.size() * 120 + out.bends.size() * 300 + 1024);
    Str(d, 999, "Cepte CAD - sheet metal flat pattern, units: mm");
    Str(d, 0, "SECTION");
    Str(d, 2, "HEADER");
    Str(d, 9, "$ACADVER");
    Str(d, 1, "AC1009");
    Str(d, 9, "$INSUNITS");
    Str(d, 70, "4");
    Str(d, 9, "$EXTMIN");
    Num(d, 10, 0);
    Num(d, 20, 0);
    Num(d, 30, 0);
    Str(d, 9, "$EXTMAX");
    Num(d, 10, out.width);
    Num(d, 20, out.height);
    Num(d, 30, 0);
    Str(d, 0, "ENDSEC");
    Str(d, 0, "SECTION");
    Str(d, 2, "TABLES");
    Str(d, 0, "TABLE");
    Str(d, 2, "LTYPE");
    Str(d, 70, "2");
    Str(d, 0, "LTYPE");
    Str(d, 2, "CONTINUOUS");
    Str(d, 70, "0");
    Str(d, 3, "Solid line");
    Str(d, 72, "65");
    Str(d, 73, "0");
    Num(d, 40, 0);
    Str(d, 0, "LTYPE");
    Str(d, 2, "DASHED");
    Str(d, 70, "0");
    Str(d, 3, "__ __ __");
    Str(d, 72, "65");
    Str(d, 73, "2");
    Num(d, 40, 6);
    Num(d, 49, 4);
    Num(d, 49, -2);
    Str(d, 0, "ENDTAB");
    Str(d, 0, "TABLE");
    Str(d, 2, "LAYER");
    Str(d, 70, "3");
    const char* layers[3][3] = {{"KESIM", "7", "CONTINUOUS"}, {"BUKUM", "1", "DASHED"}, {"NOT", "3", "CONTINUOUS"}};
    for (auto& l : layers) {
      Str(d, 0, "LAYER");
      Str(d, 2, l[0]);
      Str(d, 70, "0");
      Str(d, 62, l[1]);
      Str(d, 6, l[2]);
    }
    Str(d, 0, "ENDTAB");
    Str(d, 0, "ENDSEC");
    Str(d, 0, "SECTION");
    Str(d, 2, "ENTITIES");
    for (const Ent& e : ents) {
      if (e.kind == Ent::Line) {
        Str(d, 0, "LINE");
        Str(d, 8, "KESIM");
        Num(d, 10, e.x1);
        Num(d, 20, e.y1);
        Num(d, 30, 0);
        Num(d, 11, e.x2);
        Num(d, 21, e.y2);
        Num(d, 31, 0);
      } else if (e.kind == Ent::Circle) {
        Str(d, 0, "CIRCLE");
        Str(d, 8, "KESIM");
        Num(d, 10, e.x1);
        Num(d, 20, e.y1);
        Num(d, 30, 0);
        Num(d, 40, e.r);
      } else {
        Str(d, 0, "ARC");
        Str(d, 8, "KESIM");
        Num(d, 10, e.x1);
        Num(d, 20, e.y1);
        Num(d, 30, 0);
        Num(d, 40, e.r);
        Num(d, 50, e.a1);
        Num(d, 51, e.a2);
      }
    }
    const double th = std::max(1.5, std::min(8.0, std::max(out.width, out.height) / 60.0));
    int bi = 0;
    for (const SheetBend& b : out.bends) {
      ++bi;
      Str(d, 0, "LINE");
      Str(d, 8, "BUKUM");
      Num(d, 10, b.x1);
      Num(d, 20, b.y1);
      Num(d, 30, 0);
      Num(d, 11, b.x2);
      Num(d, 21, b.y2);
      Num(d, 31, 0);
      char label[96];
      std::snprintf(label, sizeof(label), "B%d %s %.1f%% R%.2f", bi, b.up ? "UP" : "DOWN", b.angleDeg, b.innerRadius);
      // DXF R12'de % özel karakter: %%d derece işareti
      std::string txt(label);
      const size_t pp = txt.find('%');
      if (pp != std::string::npos) txt.replace(pp, 1, "%%d");
      double ang = std::atan2(b.y2 - b.y1, b.x2 - b.x1) * 180.0 / kPi;
      if (ang > 90.0) ang -= 180.0;
      if (ang < -90.0) ang += 180.0;
      const double mx = 0.5 * (b.x1 + b.x2), my = 0.5 * (b.y1 + b.y2);
      const double nx = -std::sin(ang * kPi / 180), ny = std::cos(ang * kPi / 180);
      Str(d, 0, "TEXT");
      Str(d, 8, "NOT");
      Num(d, 10, mx + nx * th * 0.4);
      Num(d, 20, my + ny * th * 0.4);
      Num(d, 30, 0);
      Num(d, 40, th);
      Str(d, 1, txt.c_str());
      Num(d, 50, ang);
    }
    Str(d, 0, "ENDSEC");
    Str(d, 0, "EOF");
    out.status = SheetStatus::Ok;
  } catch (const Standard_Failure&) {
    out.status = SheetStatus::Failed;
  }
}

}  // namespace stepviewer
