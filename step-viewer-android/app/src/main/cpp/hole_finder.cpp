#include "hole_finder.h"

#include <BRepAdaptor_Surface.hxx>
#include <BRepClass3d_SolidClassifier.hxx>
#include <Precision.hxx>
#include <Standard_Failure.hxx>
#include <TopExp_Explorer.hxx>
#include <TopoDS.hxx>
#include <gp_Cone.hxx>
#include <gp_Cylinder.hxx>
#include <gp_Lin.hxx>

#include <algorithm>
#include <cmath>
#include <memory>

namespace stepviewer {
namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kLinTol = 1e-3;      // mm
constexpr double kAngTol = 1e-4;      // rad (eksen paralelliği)
constexpr int kMinCoverageDeg = 300;  // tam çevreye yakın olmayan (yuva ucu, köşe yuvarlatma) delik değildir

// Delik adayı yüzey: içbükey silindir veya koni.
struct AxisFace {
  int id = 0;
  bool cone = false;
  gp_Pnt loc;
  gp_Dir dir;
  double semiAngle = 0;          // koni yarı açısı (rad)
  std::vector<gp_Pnt> ring;      // orta yükseklikte çevre boyunca örnek noktalar
  gp_Pnt end1, end2;             // eksen yönünde iki uçtaki noktalar
  // Küme eksenine göre (doldurulur)
  double t1 = 0, t2 = 0;         // eksen üzerindeki konumlar
  double r1 = 0, r2 = 0;         // o konumlardaki yarıçap
};

bool AnalyseFace(const TopoDS_Face& face, int id, AxisFace& out) {
  BRepAdaptor_Surface s(face, true);
  const GeomAbs_SurfaceType type = s.GetType();
  if (type != GeomAbs_Cylinder && type != GeomAbs_Cone) return false;
  const double u1 = s.FirstUParameter(), u2 = s.LastUParameter();
  const double v1 = s.FirstVParameter(), v2 = s.LastVParameter();
  if (Precision::IsInfinite(u1) || Precision::IsInfinite(u2) || Precision::IsInfinite(v1) ||
      Precision::IsInfinite(v2) || u2 <= u1 || v2 <= v1) {
    return false;
  }
  const double um = 0.5 * (u1 + u2), vm = 0.5 * (v1 + v2);
  gp_Pnt p;
  gp_Vec du, dv;
  s.D1(um, vm, p, du, dv);
  gp_Vec n = du.Crossed(dv);
  if (n.SquareMagnitude() < 1e-24) return false;
  if (face.Orientation() == TopAbs_REVERSED) n.Reverse();

  const gp_Ax1 axis = type == GeomAbs_Cylinder ? s.Cylinder().Axis() : s.Cone().Axis();
  const gp_Vec ad(axis.Direction());
  const gp_Vec ap(axis.Location(), p);
  const gp_Vec radial = ap - ad * ap.Dot(ad);
  if (radial.SquareMagnitude() < 1e-18) return false;
  // Malzeme dışarıda (yüzey normali eksene bakıyor) ise delik; dışbükey ise mil/göbek.
  if (n.Dot(radial) >= 0) return false;

  out.id = id;
  out.cone = type == GeomAbs_Cone;
  out.loc = axis.Location();
  out.dir = axis.Direction();
  out.semiAngle = out.cone ? std::abs(s.Cone().SemiAngle()) : 0.0;
  const double span = std::min(u2 - u1, 2 * kPi);
  const int samples = std::max(4, static_cast<int>(std::ceil(span / (2 * kPi / 180.0))));
  out.ring.reserve(static_cast<size_t>(samples) + 1);
  for (int i = 0; i <= samples; ++i) {
    out.ring.push_back(s.Value(u1 + span * i / samples, vm));
  }
  out.end1 = s.Value(um, v1);
  out.end2 = s.Value(um, v2);
  return true;
}

bool IsCoaxial(const AxisFace& f, const gp_Pnt& loc, const gp_Dir& dir) {
  if (gp_Vec(f.dir).Crossed(gp_Vec(dir)).Magnitude() > kAngTol) return false;
  return gp_Lin(loc, dir).Distance(f.loc) <= kLinTol;
}

struct Interval {
  double a, b;
};

// Aralıkların birleşiminin toplam uzunluğu
double UnionLength(std::vector<Interval> v) {
  if (v.empty()) return 0;
  std::sort(v.begin(), v.end(), [](const Interval& x, const Interval& y) { return x.a < y.a; });
  double total = 0, ca = v[0].a, cb = v[0].b;
  for (size_t i = 1; i < v.size(); ++i) {
    if (v[i].a <= cb + kLinTol) {
      cb = std::max(cb, v[i].b);
    } else {
      total += cb - ca;
      ca = v[i].a;
      cb = v[i].b;
    }
  }
  return total + (cb - ca);
}

// Aynı yarıçaplı silindir yüzeyleri (bir delik çoğu zaman iki yarım yüzeyden oluşur)
struct RadiusGroup {
  double radius = 0;
  double tMin = 0, tMax = 0;
  double length = 0;
  int coverageDeg = 0;
};

// Parçanın katıları içinde mi? (malzeme var mı)
class MaterialTester {
 public:
  explicit MaterialTester(const TopoDS_Shape& part) {
    for (TopExp_Explorer ex(part, TopAbs_SOLID); ex.More(); ex.Next()) solids_.push_back(ex.Current());
  }
  bool HasSolids() const { return !solids_.empty(); }
  bool Inside(const gp_Pnt& p) {
    if (classifiers_.empty()) {
      for (const auto& s : solids_) classifiers_.push_back(std::make_unique<BRepClass3d_SolidClassifier>(s));
    }
    for (auto& c : classifiers_) {
      c->Perform(p, kLinTol);
      if (c->State() == TopAbs_IN) return true;
    }
    return false;
  }

 private:
  std::vector<TopoDS_Shape> solids_;
  std::vector<std::unique_ptr<BRepClass3d_SolidClassifier>> classifiers_;
};

void ProcessSegment(int part, const std::vector<AxisFace*>& seg, const gp_Pnt& L0, const gp_Dir& D0,
                    MaterialTester& tester, std::vector<HoleInfo>& out) {
  const gp_Vec d0(D0);
  // Ortak çerçeve (açı kapsamı için)
  const gp_Dir x0 = gp_Ax2(L0, D0).XDirection();
  const gp_Vec vx(x0), vy = d0.Crossed(vx);

  std::vector<RadiusGroup> groups;
  std::vector<std::vector<bool>> bins;
  std::vector<std::vector<Interval>> ranges;
  double s0 = 1e300, s1 = -1e300;
  for (AxisFace* f : seg) {
    s0 = std::min(s0, std::min(f->t1, f->t2));
    s1 = std::max(s1, std::max(f->t1, f->t2));
    if (f->cone) continue;
    const double r = 0.5 * (f->r1 + f->r2);
    size_t g = 0;
    for (; g < groups.size(); ++g) {
      if (std::abs(groups[g].radius - r) <= kLinTol) break;
    }
    if (g == groups.size()) {
      RadiusGroup ng;
      ng.radius = r;
      ng.tMin = 1e300;
      ng.tMax = -1e300;
      groups.push_back(ng);
      bins.emplace_back(360, false);
      ranges.emplace_back();
    }
    RadiusGroup& rg = groups[g];
    const double a = std::min(f->t1, f->t2), b = std::max(f->t1, f->t2);
    rg.tMin = std::min(rg.tMin, a);
    rg.tMax = std::max(rg.tMax, b);
    ranges[g].push_back({a, b});
    for (const gp_Pnt& p : f->ring) {
      const gp_Vec lp(L0, p);
      double ang = std::atan2(lp.Dot(vy), lp.Dot(vx)) * 180.0 / kPi;
      if (ang < 0) ang += 360.0;
      bins[g][static_cast<size_t>(std::min(359.0, std::floor(ang)))] = true;
    }
  }
  int main = -1;
  for (size_t g = 0; g < groups.size(); ++g) {
    groups[g].length = UnionLength(ranges[g]);
    groups[g].coverageDeg = static_cast<int>(std::count(bins[g].begin(), bins[g].end(), true));
    if (groups[g].coverageDeg >= kMinCoverageDeg &&
        (main < 0 || groups[g].radius < groups[static_cast<size_t>(main)].radius)) {
      main = static_cast<int>(g);
    }
  }
  if (main < 0) return;
  const RadiusGroup& mg = groups[static_cast<size_t>(main)];

  HoleInfo h;
  h.part = part;
  h.diameter = 2 * mg.radius;
  h.depth = mg.length;

  // Silindirik havşa (kaçak): ana bölümün bir ucuna bitişik daha büyük tam silindir
  int cboreSide = 0;  // -1: tMin tarafı, +1: tMax tarafı
  double cboreFar = 0;
  for (const RadiusGroup& g : groups) {
    if (g.radius <= mg.radius + kLinTol || g.coverageDeg < kMinCoverageDeg) continue;
    const double tol = std::max(kLinTol * 10, 0.01 * h.diameter);
    if (std::abs(g.tMax - mg.tMin) <= tol && g.radius * 2 > h.cboreDiameter) {
      h.cboreDiameter = 2 * g.radius;
      h.cboreDepth = g.length;
      cboreSide = -1;
      cboreFar = g.tMin;
    } else if (std::abs(g.tMin - mg.tMax) <= tol && g.radius * 2 > h.cboreDiameter) {
      h.cboreDiameter = 2 * g.radius;
      h.cboreDepth = g.length;
      cboreSide = +1;
      cboreFar = g.tMax;
    }
  }

  // Koniler: dışa doğru genişleyen = konik havşa, daralan = matkap ucu
  int csinkSide = 0, pointSide = 0;
  const double endTol = std::max(kLinTol * 10, 0.01 * h.diameter);
  for (AxisFace* f : seg) {
    if (!f->cone) continue;
    const double a = std::min(f->t1, f->t2), b = std::max(f->t1, f->t2);
    const double ra = f->t1 <= f->t2 ? f->r1 : f->r2;  // a ucundaki yarıçap
    const double rb = f->t1 <= f->t2 ? f->r2 : f->r1;
    // tMin tarafındaki koni: b ucu deliğe bitişik, a ucu dışarıda
    const double lowEdge = cboreSide == -1 ? cboreFar : mg.tMin;
    const double highEdge = cboreSide == +1 ? cboreFar : mg.tMax;
    int side = 0;
    double rNear = 0, rFar = 0;
    if (std::abs(b - lowEdge) <= endTol) {
      side = -1;
      rNear = rb;
      rFar = ra;
    } else if (std::abs(a - highEdge) <= endTol) {
      side = +1;
      rNear = ra;
      rFar = rb;
    } else {
      continue;
    }
    if (rFar > rNear + kLinTol) {
      if (2 * rFar > h.csinkDiameter) {
        h.csinkDiameter = 2 * rFar;
        h.csinkAngle = 2 * f->semiAngle * 180.0 / kPi;
        csinkSide = side;
      }
    } else if (rFar < rNear - kLinTol) {
      h.drillPoint = true;
      pointSide = side;
    }
  }

  // Uçların arkasında malzeme var mı? (kör delik tespiti)
  const double eps = std::max(0.05, 0.05 * h.diameter);
  bool closedLow = false, closedHigh = false;
  if (tester.HasSolids()) {
    const gp_Pnt pl = L0.Translated(d0 * (s0 - eps));
    const gp_Pnt ph = L0.Translated(d0 * (s1 + eps));
    closedLow = tester.Inside(pl);
    closedHigh = tester.Inside(ph);
  } else if (h.drillPoint) {
    closedLow = pointSide == -1;
    closedHigh = pointSide == +1;
  }
  if (closedLow && closedHigh) return;  // tamamen içerde boşluk: delik değil
  h.through = !closedLow && !closedHigh;

  // Giriş tarafı: kapalı ucun karşısı; boydan boyada havşalı taraf
  int entrySide;
  if (closedLow) {
    entrySide = +1;
  } else if (closedHigh) {
    entrySide = -1;
  } else if (cboreSide != 0) {
    entrySide = cboreSide;
  } else if (csinkSide != 0) {
    entrySide = csinkSide;
  } else {
    entrySide = -1;
  }
  const double te = entrySide < 0 ? s0 : s1;
  const gp_Pnt e = L0.Translated(d0 * te);
  h.entry[0] = e.X();
  h.entry[1] = e.Y();
  h.entry[2] = e.Z();
  const gp_Vec in = entrySide < 0 ? d0 : -d0;
  h.dir[0] = in.X();
  h.dir[1] = in.Y();
  h.dir[2] = in.Z();
  // Havşa giriş tarafında değilse (ör. alttan kaçak) yine de bilgi olarak kalır
  for (AxisFace* f : seg) h.faces.push_back(f->id);
  out.push_back(std::move(h));
}

}  // namespace

void FindHoles(const std::vector<TopoDS_Face>& faces, const std::vector<int32_t>& partFaceOffsets,
               const std::vector<TopoDS_Shape>& parts, std::vector<HoleInfo>& out) {
  out.clear();
  const int partCount = static_cast<int>(partFaceOffsets.size()) - 1;
  for (int p = 0; p < partCount; ++p) {
    const int f0 = partFaceOffsets[static_cast<size_t>(p)];
    const int f1 = partFaceOffsets[static_cast<size_t>(p) + 1];
    std::vector<AxisFace> cand;
    for (int f = f0; f < f1 && f < static_cast<int>(faces.size()); ++f) {
      try {
        AxisFace af;
        if (AnalyseFace(faces[static_cast<size_t>(f)], f, af)) cand.push_back(std::move(af));
      } catch (const Standard_Failure&) {
      }
    }
    if (cand.empty()) continue;

    // Eş eksenli yüzeyleri kümele
    struct Cluster {
      gp_Pnt loc;
      gp_Dir dir;
      std::vector<AxisFace*> members;
    };
    std::vector<Cluster> clusters;
    for (AxisFace& f : cand) {
      Cluster* found = nullptr;
      for (Cluster& c : clusters) {
        if (IsCoaxial(f, c.loc, c.dir)) {
          found = &c;
          break;
        }
      }
      if (found == nullptr) {
        clusters.push_back({f.loc, f.dir, {}});
        found = &clusters.back();
      }
      found->members.push_back(&f);
    }

    MaterialTester tester(p < static_cast<int>(parts.size()) ? parts[static_cast<size_t>(p)] : TopoDS_Shape());
    for (Cluster& c : clusters) {
      const gp_Vec d0(c.dir);
      const gp_Lin axis(c.loc, c.dir);
      for (AxisFace* f : c.members) {
        f->t1 = gp_Vec(c.loc, f->end1).Dot(d0);
        f->t2 = gp_Vec(c.loc, f->end2).Dot(d0);
        f->r1 = axis.Distance(f->end1);
        f->r2 = axis.Distance(f->end2);
      }
      // Eksen boyunca birbirine değen yüzeyler aynı deliktir
      std::sort(c.members.begin(), c.members.end(), [](const AxisFace* a, const AxisFace* b) {
        return std::min(a->t1, a->t2) < std::min(b->t1, b->t2);
      });
      std::vector<AxisFace*> seg;
      double segEnd = -1e300;
      for (AxisFace* f : c.members) {
        const double a = std::min(f->t1, f->t2), b = std::max(f->t1, f->t2);
        if (!seg.empty() && a > segEnd + kLinTol * 10) {
          try {
            ProcessSegment(p, seg, c.loc, c.dir, tester, out);
          } catch (const Standard_Failure&) {
          }
          seg.clear();
        }
        seg.push_back(f);
        segEnd = seg.size() == 1 ? b : std::max(segEnd, b);
      }
      if (!seg.empty()) {
        try {
          ProcessSegment(p, seg, c.loc, c.dir, tester, out);
        } catch (const Standard_Failure&) {
        }
      }
    }
  }
}

}  // namespace stepviewer
