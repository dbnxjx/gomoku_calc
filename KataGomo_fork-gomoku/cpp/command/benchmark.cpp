#include "../core/global.h"
#include "../core/config_parser.h"
#include "../core/timer.h"
#include "../dataio/sgf.h"
#include "../search/asyncbot.h"
#include "../program/setup.h"
#include "../program/playutils.h"
#include "../program/gtpconfig.h"
#include "../command/commandline.h"
#include "../main.h"

#include <chrono>
#include <map>
#include <sstream>
#include <fstream>

#include <ghc/filesystem.hpp>
namespace gfs = ghc::filesystem;

using namespace std;

static NNEvaluator* createNNEval(int maxNumThreads, CompactSgf* sgf, const string& modelFile, Logger& logger, ConfigParser& cfg, const SearchParams& params);

static vector<PlayUtils::BenchmarkResults> doFixedTuneThreads(
  const SearchParams& params,
  const CompactSgf* sgf,
  int numPositionsPerGame,
  NNEvaluator*& nnEval,
  Logger& logger,
  double secondsPerGameMove,
  vector<int> numThreadsToTest,
  bool printElo
);
static vector<PlayUtils::BenchmarkResults> doAutoTuneThreads(
  const SearchParams& params,
  const CompactSgf* sgf,
  int numPositionsPerGame,
  NNEvaluator*& nnEval,
  Logger& logger,
  double secondsPerGameMove,
  std::function<void(int)> reallocateNNEvalWithEnoughBatchSize
);

#ifdef USE_EIGEN_BACKEND
static const int64_t defaultMaxVisits = 80;
#else
static const int64_t defaultMaxVisits = 800;
#endif

static constexpr double defaultSecondsPerGameMove = 5.0;
static const int ternarySearchInitialMax = 32;

static string getBenchmarkSGFData(int boardSize) {
  string sgfData;
  if (boardSize == 19) {
    sgfData = "(;FF[4]GM[1]SZ[19]HA[0]KM[7.5];B[dd];W[pp];B[dp];W[pd];B[qq];W[pq];B[qp];W[qo];B[ro];W[rn];B[qn];W[po];B[rm];W[rp];B[sn];W[rq];B[nc];W[oc];B[qr];W[rr];B[nd];W[pf];B[re];W[lc];B[jc];W[le];B[qc];W[qd];B[ob];W[pc];B[pb];W[rc];B[qb];W[rd];B[lb];W[mb];B[ld];W[kb];B[kc];W[la];B[mc];W[qi];B[ke];W[cc];B[dc];W[cd];B[cf];W[ce];B[de];W[bf];B[cb];W[bb];B[be];W[db];B[bc];W[ca];B[bd];W[cb];B[bg];W[df];B[cg];W[fc];B[nr];W[nq];B[pg];W[qg];B[of];W[ef];B[mq];W[cq];B[dq];W[cp];B[co];W[bo];B[bn];W[cn];B[do];W[bm];B[bp];W[an];B[bq];W[cr];B[br];W[kq];B[iq];W[ko];B[kr];W[cj];B[lq];W[bi];B[qj];W[ph];B[og];W[rf];B[gc];W[gd];B[hc];W[nb];B[rb];W[sb];B[lb];W[fe];B[ri];W[rh];B[pi];W[qh];B[af];W[lc];B[dn];W[dm];B[em];W[in];B[lb];W[fn];B[lc];W[en];B[fp];W[fm];B[pj];W[hp];B[hq];W[ij];B[di];W[dj];B[jl];W[il];B[jm];W[im];B[jk];W[jj];B[kj];W[ki];B[lj];W[li];B[mi];W[jh];B[pn];W[or];B[np];W[ie];B[dh];W[ei];B[eh];W[fh];B[kn];W[oo];B[mn];W[oh];B[ni];W[oq];B[fg];W[gh];B[hg];W[hh];B[ig];W[ih];B[fb];W[eb];B[gb];W[mj];B[mg];W[kf];B[lf];W[je];B[kg];W[kd];B[jg];W[ln];B[lm];W[lo];B[nn];W[ra];B[na];W[gp];B[gq];W[fo];B[rj];W[oe];B[me];W[mr];B[lr];W[ns];B[sp];W[on];B[om];W[pm];B[nm];W[ip];B[jp];W[jo];B[kp];W[lp];B[jq];W[ap];B[dr];W[ar];B[cs];W[aq];B[sr];W[qs];B[ab];W[ea];B[ec];W[fd];B[fa];W[ee];B[ke];W[oi];B[oj];W[le];B[ik];W[hk];B[ke];W[ep];B[fq];W[le];B[eo];W[ke];B[sq];W[pr];B[qm];W[ml];B[mk];W[lk];B[nj];W[kk];B[mj];W[km];B[kl];W[qa];B[hd];W[he];B[gf];W[ge];B[bh];W[nf];B[ng];W[ne];B[ci];W[ai];B[mf];W[eg];B[gg];W[dg];B[ch];W[si];B[ah];W[bj];B[sj];W[sh];B[ma];W[sc];B[pa];W[id];B[ic];W[ls];B[ks];W[ms];B[sa];W[ra];B[od];W[pe];B[kh];W[lh];B[ji];W[ii];B[mh];W[ji];B[lg];W[mp];B[no];W[jn];B[km];W[as];B[fi];W[ej])";
  }
  else if (boardSize == 18) {
    sgfData = "(;FF[4]GM[1]SZ[18]HA[0]KM[7.5];B[od];W[do];B[oo];W[dd];B[cp];W[dp];B[co];W[cn];B[bn];W[bm];B[cm];W[dn];B[bl];W[bo];B[am];W[bp];B[cq];W[bq];B[fc];W[pp];B[op];W[po];B[pm];W[pn];B[on];W[qm];B[pq];W[pc];B[qn];W[rn];B[ql];W[qo];B[pl];W[qq];B[pd];W[oc];B[nd];W[mb];B[cf];W[fd];B[gd];W[fe];B[dc];W[cc];B[ec];W[df];B[ed];W[de];B[cb];W[bd];B[bc];W[cd];B[ch];W[bb];B[ba];W[ac];B[lc];W[lb];B[kc];W[mk];B[lm];W[mi];B[lj];W[mj];B[kl];W[mm];B[mn];W[nm];B[pi];W[ln];B[kn];W[lo];B[om];W[ko];B[jo];W[jp];B[io];W[ip];B[ho];W[hp];B[kh];W[oi];B[ph];W[oh];B[go];W[gp];B[fp];W[fq];B[fo];W[lq];B[eq];W[dq];B[mp];W[pg];B[og];W[ng];B[of];W[pj];B[qj];W[qi];B[qh];W[qk];B[ri];W[pk];B[rk];W[ki];B[li];W[lh];B[kj];W[ji];B[jj];W[kg];B[jh];W[ii];B[jg];W[kf];B[gq];W[hq];B[hh];W[mg];B[fr];W[lp];B[eh];W[dm];B[nk];W[oj];B[rl];W[dk];B[hf];W[gg];B[gh];W[fg];B[fh];W[db];B[da];W[ca];B[nq];W[ea];B[rp];W[rq];B[rm];W[qr];B[qn];W[cj];B[bj];W[qm];B[qc];W[qb];B[qn];W[pr];B[oq];W[qm];B[qd];W[gl];B[fm];W[gj];B[el];W[dl];B[ek];W[ej];B[fj];W[ij];B[il];W[bi];B[bk];W[ci];B[cl];W[ih];B[ig];W[fk];B[fi];W[ik];B[jk];W[im];B[jl];W[jn];B[hm];W[km];B[ll];W[ml];B[in];W[qn];B[lf];W[le];B[mf];W[lg];B[kb];W[hg];B[je];W[ie];B[he];W[id];B[if];W[gc];B[hd];W[gb];B[ol];W[jd];B[hc];W[kd];B[hb];W[md];B[mc];W[nb];B[me];W[jf];B[rb];W[oa];B[fb];W[cg];B[bh];W[bg];B[dh];W[ah];B[mo];W[ld];B[nf];W[jb];B[jc];W[ka];B[ic];W[ke];B[fl];W[eb];B[ga];W[hr];B[ia];W[ja];B[qa];W[pb];B[gk];W[kn];B[aj];W[ai];B[nj];W[ni];B[jm];W[ao];B[an];W[nn];B[no];W[lk];B[dr];W[cr];B[en];W[er];B[ib];W[la];B[dr];W[mr];B[jq];W[jr];B[kr];W[kq];B[ir];W[iq];B[mq];W[jr];B[er];W[pa];B[rc];W[ra])";
  }
  else if (boardSize == 17) {
    sgfData = "(;FF[4]GM[1]SZ[17]HA[0]KM[7.5];B[dd];W[nn];B[nd];W[dn];B[oo];W[on];B[no];W[mo];B[mp];W[lp];B[lo];W[mn];B[kp];W[np];B[lq];W[op];B[po];W[pp];B[cn];W[co];B[cm];W[do];B[dm];W[cc];B[cd];W[dc];B[fc];W[ec];B[ed];W[fb];B[bc];W[bb];B[gb];W[fd];B[gc];W[bd];B[pl];W[pm];B[be];W[ac];B[fe];W[nf];B[of];W[og];B[oe];W[ng];B[ld];W[kg];B[ig];W[ke];B[go];W[fn];B[fl];W[fo];B[ji];W[bf];B[cf];W[ae];B[li];W[mj];B[lf];W[ne];B[md];W[od];B[pd];W[oc];B[pc];W[ob];B[pg];W[ph];B[pb];W[lg];B[mf];W[mg];B[kf];W[mb];B[lb];W[kb];B[lc];W[pf];B[pe];W[qg];B[ch];W[la];B[pa];W[jf];B[jg];W[hb];B[jc];W[hc];B[gd];W[ga];B[jb];W[ce];B[hd];W[cg];B[bh];W[ho];B[ip];W[kk];B[mi];W[nj];B[ik];W[hl];B[hk];W[dg];B[dh];W[fg];B[eh];W[ff];B[gp];W[gn];B[hp];W[jo];B[jp];W[bo];B[bn];W[hf];B[if];W[ln];B[io];W[ko];B[hn];W[fp];B[gh];W[ha];B[ib];W[jj];B[ij];W[jm];B[lp];W[il];B[gl];W[jk];B[kh];W[hg];B[hh];W[an];B[am];W[em];B[el];W[ao];B[fq];W[ep];B[ni];W[oi];B[eb];W[fa];B[ic];W[db];B[df];W[de];B[ef];W[eg];B[ee];W[bg];B[nq];W[in];B[pq];W[qo];B[oq];W[pn];B[gq];W[lj];B[ki];W[he];B[ie];W[be];B[ge];W[ah];B[ai];W[ag];B[bj];W[qe];B[qd];W[hm];B[eq];W[dq];B[fh];W[me];B[le];W[qf];B[fm];W[en];B[ia];W[ea];B[nh];W[oh];B[ho];W[qq];B[mq];W[gm];B[qp];W[dl];B[cl];W[qq];B[kj];W[mh];B[qp];W[dk];B[ck];W[qq];B[gk];W[gf];B[qp];W[qb];B[nb];W[qq];B[oj];W[pj];B[qp];W[nc];B[na];W[qq];B[lh];W[gg];B[qp];W[ma];B[oa];W[qq];B[dp];W[cp];B[qp];W[id];B[jd];W[qq];B[cq];W[bq];B[qp];W[bi];B[ci];W[qq];B[qi];W[ok];B[qp];W[bm];B[bl];W[qq];B[qh];W[pg];B[qp];W[iq];B[jq];W[qq];B[pi];W[qj];B[qp];W[qa];B[mc];W[qq];B[da];W[ca];B[qp];W[jh];B[ii];W[qq];B[kn];W[jn];B[qp];W[aj];B[qq];W[bi];B[po];W[ai];B[qn];W[qm])";
  }
  else if (boardSize == 16) {
    sgfData = "(;FF[4]GM[1]SZ[16]HA[0]KM[7.5];B[mm];W[dd];B[md];W[dm];B[cc];W[cd];B[dc];W[ed];B[fb];W[ck];B[nf];W[nn];B[mn];W[nm];B[nl];W[ol];B[ok];W[nk];B[ml];W[oj];B[om];W[pk];B[on];W[no];B[oo];W[fn];B[bc];W[gd];B[ni];W[mk];B[dj];W[cj];B[dh];W[gh];B[ci];W[cg];B[dk];W[bi];B[dl];W[cl];B[em];W[en];B[hi];W[gi];B[hk];W[hj];B[fm];W[gm];B[fk];W[ij];B[dn];W[cm];B[gn];W[go];B[hn];W[ho];B[in];W[io];B[jn];W[jo];B[do];W[eo];B[ep];W[fo];B[cn];W[kn];B[bm];W[jm];B[gj];W[gl];B[bl];W[bk];B[gk];W[al];B[bn];W[mc];B[nc];W[lc];B[nb];W[me];B[nd];W[ld];B[hc];W[ch];B[di];W[fi];B[jj];W[ii];B[jl];W[mo];B[lo];W[ll];B[ln];W[im];B[hl];W[hm];B[kk];W[lk];B[kh];W[fl];B[ig];W[ih];B[jh];W[ie];B[el];W[lg];B[mh];W[jg];B[kg];W[jf];B[kf];W[ke];B[lf];W[le];B[og];W[bd];B[jd];W[je];B[hd];W[li];B[lj];W[mj];B[kj];W[ac];B[ab];W[ad];B[cb];W[he];B[dg];W[jb];B[am];W[mi];B[bj];W[aj];B[ak];W[lm];B[np];W[al];B[cf];W[bf];B[ak];W[ko];B[mp];W[al];B[kl];W[oi];B[ak];W[gb];B[gc];W[al];B[nh];W[km];B[ak];W[ne];B[oe];W[al];B[hg];W[gg];B[ak];W[fc];B[ec];W[fd];B[hb];W[al];B[hh];W[hf];B[ak];W[eb];B[ga];W[al];B[ik];W[if];B[ak];W[ca];B[ea];W[al];B[mb];W[ak];B[lb];W[lh];B[mf];W[id];B[kb];W[ao];B[bo];W[cp];B[dp];W[bp];B[jc];W[ic];B[ib];W[kc];B[ef];W[ja];B[bg];W[bh];B[be];W[af];B[ce];W[ff];B[ee];W[fe];B[fj];W[ei];B[ej];W[oh];B[ph];W[pi];B[pg];W[kd];B[pm];W[mg];B[ng];W[ae];B[pl];W[ok];B[fp];W[eg];B[df];W[eh];B[gp];W[ia];B[ha])";
  }
  else if (boardSize == 15) {
    sgfData = "(;FF[4]GM[1]SZ[15]HA[0]KM[7.5];B[dd];W[ll];B[ld];W[dl];B[dm];W[cm];B[em];W[cl];B[im];W[mc];B[lc];W[md];B[le];W[nf];B[el];W[km];B[dj];W[bj];B[mj];W[mg];B[nl];W[mm];B[kk];W[jl];B[nm];W[il];B[hm];W[hl];B[bi];W[cj];B[ci];W[gm];B[gn];W[gl];B[ei];W[fn];B[cn];W[bn];B[en];W[co];B[fm];W[dc];B[ec];W[cd];B[ed];W[cc];B[in];W[ii];B[cf];W[ce];B[df];W[eb];B[fb];W[da];B[ki];W[ig];B[lg];W[gh];B[gj];W[jj];B[kj];W[fj];B[fk];W[gf];B[gi];W[hd];B[hh];W[ih];B[hi];W[hj];B[hg];W[hf];B[if];W[gk];B[fi];W[lh];B[ie];W[kg];B[lf];W[kh];B[gc];W[nn];B[nh];W[mh];B[ni];W[ml];B[mk];W[om];B[ok];W[on];B[ng];W[og];B[lk];W[li];B[kl];W[mf];B[mn];W[lm];B[no];W[ln];B[mo];W[lo];B[jm];W[oo];B[mo];W[jn];B[jo];W[ko];B[kn];W[id];B[mn];W[jc];B[kb];W[jb];B[mb];W[nb];B[je];W[gd];B[na];W[nc];B[ff];W[jd];B[hc];W[ke];B[kf];W[kd];B[gg];W[he];B[jf];W[fe];B[ja];W[ia];B[la];W[ka];B[dn];W[ef];B[de];W[bl];B[ja];W[ib];B[fg];W[fd];B[fc];W[ka];B[jk];W[ik];B[ja];W[kc];B[hb];W[me];B[ee];W[jg];B[lb];W[ka];B[bo];W[ao];B[ja];W[oa];B[ob];W[ka];B[am];W[dk];B[ja];W[bf];B[ha];W[ka];B[an];W[bo];B[ja];W[ek];B[ej];W[ka];B[aj];W[ak];B[ja];W[ai];B[bg];W[be];B[af];W[ka];B[ji];W[ij];B[ja];W[ae];B[ag];W[ka];B[ea];W[ma];B[bb];W[cb];B[db];W[ca];B[bc];W[eb];B[fa];W[ab];B[ac])";
  }
  else if (boardSize == 14) {
    sgfData = "(;FF[4]GM[1]SZ[14]HA[0]KM[7.5];B[dk];W[kd];B[kk];W[dd];B[dc];W[cc];B[ec];W[cb];B[ic];W[ed];B[fd];W[jc];B[gc];W[ef];B[id];W[le];B[cg];W[bf];B[il];W[cl];B[dl];W[ck];B[cj];W[bj];B[bi];W[ci];B[dj];W[bh];B[bk];W[ai];B[bl];W[cm];B[bm];W[di];B[lg];W[jb];B[lj];W[fe];B[ei];W[eh];B[fi];W[kg];B[kh];W[he];B[ib];W[jg];B[hd];W[jh];B[ja];W[lh];B[kb];W[lc];B[lb];W[mb];B[db];W[hk];B[hi];W[ik];B[jk];W[hl];B[ij];W[jl];B[jm];W[hj];B[ii];W[gi];B[gh];W[gj];B[fh];W[im];B[km];W[em];B[dm];W[fk];B[gm];W[hh];B[fj];W[el];B[gl];W[en];B[gk];W[hm];B[gn];W[cn];B[dn];W[ej];B[bn];W[ek];B[gf];W[jj];B[eg];W[ih];B[dh];W[mi];B[in];W[ji];B[hn];W[ll];B[kl];W[mj];B[mk];W[ml];B[lk];W[nk];B[mm];W[nm];B[lm];W[bg];B[cd];W[ce];B[ge];W[bd];B[ma];W[mc];B[ca];W[ba];B[da];W[mn];B[li];W[mh];B[ki];W[mg];B[bb];W[bc];B[aa];W[je];B[ff];W[ee];B[nl];W[ml];B[if];W[ie];B[hf];W[jf])";
  }
  else if (boardSize == 13) {
    sgfData = "(;FF[4]GM[1]SZ[13]HA[0]KM[7.5];B[dd];W[jj];B[kk];W[jd];B[kj];W[dj];B[jc];W[dc];B[cc];W[ec];B[ed];W[fc];B[fd];W[ic];B[hc];W[kc];B[gc];W[ch];B[jb];W[ib];B[id];W[ie];B[hd];W[kb];B[je];W[ja];B[kd];W[jc];B[jf];W[jk];B[ji];W[ii];B[fk];W[kl];B[ll];W[il];B[km];W[dk];B[hk];W[jg];B[kg];W[jh];B[ki];W[gj];B[hj];W[gk];B[hl];W[kh];B[if];W[lg];B[gi];W[li];B[jl];W[le];B[bg];W[bh];B[cg];W[dg];B[df];W[gb];B[dh];W[di];B[eg];W[cb];B[bb];W[db];B[bj];W[bi];B[aj];W[ai];B[cl];W[bk];B[dl];W[fj];B[fl];W[fi];B[cj];W[ck];B[bl];W[gh];B[hi];W[eh];B[ig];W[al];B[ek];W[ej];B[fg];W[fh];B[ih];W[kf];B[ba];W[gl];B[gm];W[hb];B[ke];W[ld];B[gg];W[ag];B[bf];W[bc];B[cd];W[ca];B[bd];W[lj];B[lk];W[hh];B[hg];W[mk];B[ml];W[mj];B[dg];W[bm];B[cm];W[ci];B[am];W[ac];B[ad];W[bm];B[af];W[ah];B[am];W[em];B[ak];W[al];B[fm];W[bm];B[ak];W[aj];B[am];W[ab];B[aa];W[bm];B[fb];W[fa];B[am];W[el];B[dm];W[bm];B[hm];W[ak];B[am];W[em];B[el];W[bm];B[ea];W[eb];B[am];W[gd])";
  }
  else if (boardSize == 12) {
    sgfData = "(;FF[4]GM[1]SZ[12]HA[0]KM[7.5];B[ii];W[dd];B[cc];W[cd];B[dc];W[ed];B[ec];W[di];B[fd];W[jd];B[cg];W[fe];B[gd];W[bc];B[jf];W[hc];B[hd];W[ic];B[eg];W[be];B[fi];W[gf];B[gb];W[fj];B[gj];W[ej];B[gk];W[fh];B[fg];W[if];B[je];W[gi];B[gg];W[hi];B[hh];W[hj];B[ij];W[hk];B[ik];W[il];B[jl];W[hl];B[hb];W[ie];B[id];W[jg];B[kg];W[kh];B[ig];W[jh];B[hf];W[ih];B[he];W[kf];B[ke];W[lg];B[bb];W[ab];B[ba];W[bg];B[bh];W[cf];B[ag];W[bf];B[ch];W[df];B[eh];W[ei];B[gh];W[jk];B[ck];W[le];B[ld];W[lf];B[kd];W[cj];B[bj];W[dk];B[ci];W[cl];B[ee];W[ef];B[ff];W[de];B[fi];W[kl];B[dj];W[bk];B[cj];W[fk];B[ak];W[bl];B[af];W[ae];B[ah];W[fh];B[ge];W[fi];B[ee];W[cb];B[db];W[fe];B[ac];W[ad];B[ee];W[dl];B[aj];W[fe];B[li];W[ki];B[ee];W[ac];B[al];W[fe];B[ek];W[el];B[ee];W[aa];B[ca];W[fe];B[lh];W[lj];B[ee];W[ea];B[da])";
  }
  else if (boardSize == 11) {
    sgfData = "(;FF[4]GM[1]SZ[11]HA[0]KM[7.5];B[hd];W[dh];B[dc];W[hh];B[cf];W[id];B[ie];W[he];B[hc];W[dd];B[cd];W[ec];B[de];W[ed];B[db];W[ic];B[ib];W[jb];B[hb];W[je];B[if];W[jf];B[ig];W[ge];B[ih];W[fb];B[ja];W[kb];B[jd];W[jc];B[kd];W[ke];B[jg];W[kc];B[eb];W[fc];B[ee];W[fe];B[gg];W[ff];B[fi];W[ej];B[bh];W[hi];B[fg];W[fj];B[ef];W[gd];B[ii];W[cj];B[ci];W[bj];B[gj];W[gi];B[hj];W[di];B[hg];W[ch];B[bg];W[bi];B[eh];W[dg];B[ea];W[gb];B[ai];W[aj];B[ah];W[kg];B[kh];W[ei];B[fh];W[gk];B[hk];W[fk];B[cg];W[eg];B[kf];W[cc];B[bc];W[df];B[ce];W[kg];B[jd];W[kd];B[cb];W[kf];B[hf];W[ga];B[fa];W[ha];B[ia];W[gc];B[gf])";
  }
  else if (boardSize == 10) {
    sgfData = "(;FF[4]GM[1]SZ[10]HA[0]KM[7.5];B[gc];W[gg];B[dh];W[cd];B[dc];W[cg];B[ch];W[cc];B[he];W[hf];B[ie];W[fh];B[bg];W[gd];B[hc];W[ec];B[db];W[dd];B[eb];W[bf];B[cf];W[dg];B[be];W[bh];B[af];W[bi];B[eg];W[df];B[ef];W[de];B[eh];W[ce];B[ci];W[bf];B[gf];W[hh];B[cf];W[bj];B[cj];W[bf];B[fi];W[gi];B[fg];W[gh];B[if];W[ei];B[cf];W[fe];B[ff];W[bf];B[ah];W[ag];B[ii];W[ih];B[bg];W[hd];B[id];W[ag];B[ej];W[fj];B[bg];W[dj];B[cf];W[fc];B[fb];W[bf];B[di];W[ag];B[ej];W[bg];B[ji];W[dj];B[jh];W[ge];B[hg];W[ig])";
  }
  else if (boardSize == 9) {
    sgfData = "(;FF[4]GM[1]SZ[9]HA[0]KM[7.5];B[ef];W[ed];B[ge];W[gc];B[cc];W[cd];B[bd];W[ce];B[be];W[dg];B[cf];W[df];B[de];W[dd];B[ee];W[cg];B[bf];W[cb];B[eg];W[bc];B[bh];W[he];B[hd];W[gf];B[fe];W[hf];B[fc];W[eb];B[gd];W[fh];B[eh];W[hh];B[ac];W[dc];B[fb];W[ab];B[fg];W[gg];B[fi];W[bg];B[dh];W[gh];B[ea];W[da];B[fa];W[ad];B[ch];W[id];B[ic];W[ie];B[gb];W[gi];B[ec];W[hc];B[hb];W[ei];B[db];W[ae];B[ag];W[eb];B[ig];W[db];B[ih];W[ii];B[di];W[ac];B[fi];W[hg];B[ei];W[af];B[ff];W[if];B[fd];W[bb])";
  }
  else {
    throw StringError("getBenchmarkSGFData: unsupported board size");
  }
  return sgfData;
}
int MainCmds::benchmark(int argc, const char* const* argv) {
  Board::initHash();
   

  ConfigParser cfg;
  string modelFile;
  string sgfFile;
  int boardSize;
  int64_t maxVisits;
  vector<int> numThreadsToTest;
  int numPositionsPerGame;
  bool autoTuneThreads;
  double secondsPerGameMove;
  try {
    KataGoCommandLine cmd("Benchmark with gtp config to test speed with different numbers of threads.");
    cmd.addConfigFileArg(KataGoCommandLine::defaultGtpConfigFileName(),"gtp_example.cfg");
    cmd.addModelFileArg();
    TCLAP::ValueArg<long> visitsArg("v","visits","How many visits to use per search (default " + Global::int64ToString(defaultMaxVisits) + ")",false,(long)defaultMaxVisits,"VISITS");
    TCLAP::ValueArg<string> threadsArg("t","threads","Test these many threads, comma-separated, e.g. '4,8,12,16' ",false,"","THREADS");
    TCLAP::ValueArg<int> numPositionsPerGameArg("n","numpositions","How many positions to sample from a game (default 10)",false,10,"NUM");
    TCLAP::ValueArg<string> sgfFileArg("","sgf", "Optional game to sample positions from (default: uses a built-in-set of positions)",false,string(),"FILE");
    TCLAP::ValueArg<int> boardSizeArg("","boardsize", "Size of board to benchmark on (9-19), default 19",false,-1,"SIZE");
    TCLAP::SwitchArg autoTuneThreadsArg("s","tune","Automatically search for the optimal number of threads (default if not specifying specific numbers of threads)");
    TCLAP::ValueArg<double> secondsPerGameMoveArg("i","time","Typical amount of time per move spent while playing, in seconds (default " +
                                               Global::doubleToString(defaultSecondsPerGameMove) + ")",false,defaultSecondsPerGameMove,"SECONDS");
    cmd.add(visitsArg);
    cmd.add(threadsArg);
    cmd.add(numPositionsPerGameArg);

    cmd.setShortUsageArgLimit();

    cmd.addOverrideConfigArg();

    cmd.add(sgfFileArg);
    cmd.add(boardSizeArg);
    cmd.add(autoTuneThreadsArg);
    cmd.add(secondsPerGameMoveArg);
    cmd.parse(argc,argv);

    modelFile = cmd.getModelFile();
    sgfFile = sgfFileArg.getValue();
    boardSize = boardSizeArg.getValue();
    maxVisits = (int64_t)visitsArg.getValue();
    string desiredThreadsStr = threadsArg.getValue();
    numPositionsPerGame = numPositionsPerGameArg.getValue();
    autoTuneThreads = autoTuneThreadsArg.getValue();
    secondsPerGameMove = secondsPerGameMoveArg.getValue();

    if(boardSize != -1 && sgfFile != "")
      throw StringError("Cannot specify both -sgf and -boardsize at the same time");
    if(boardSize != -1 && (boardSize < 9 || boardSize > 19))
      throw StringError("Board size to test: invalid value " + Global::intToString(boardSize));
    if(maxVisits <= 1 || maxVisits >= 1000000000)
      throw StringError("Number of visits to use: invalid value " + Global::int64ToString(maxVisits));
    if(numPositionsPerGame <= 0 || numPositionsPerGame > 100000)
      throw StringError("Number of positions per game to use: invalid value " + Global::intToString(numPositionsPerGame));
    if(secondsPerGameMove <= 0 || secondsPerGameMove > 1000000)
      throw StringError("Number of seconds per game move to assume: invalid value " + Global::doubleToString(secondsPerGameMove));
    if(desiredThreadsStr != "" && autoTuneThreads)
      throw StringError("Cannot both automatically tune threads and specify fixed exact numbers of threads to test");

    //Apply default
    if(desiredThreadsStr == "")
      autoTuneThreads = true;

    if(!autoTuneThreads) {
      vector<string> desiredThreadsPieces = Global::split(desiredThreadsStr,',');
      for(int i = 0; i<desiredThreadsPieces.size(); i++) {
        string s = Global::trim(desiredThreadsPieces[i]);
        if(s == "")
          continue;
        int desiredThreads;
        bool suc = Global::tryStringToInt(s,desiredThreads);
        if(!suc || desiredThreads <= 0 || desiredThreads > 1024)
          throw StringError("Number of threads to use: invalid value: " + s);
        numThreadsToTest.push_back(desiredThreads);
      }

      if(numThreadsToTest.size() <= 0) {
        throw StringError("Must specify at least one valid value for -threads");
      }
    }

    cmd.getConfig(cfg);
  }
  catch (TCLAP::ArgException &e) {
    cerr << "Error: " << e.error() << " for argument " << e.argId() << endl;
    return 1;
  }

  CompactSgf* sgf;
  if(sgfFile != "") {
    sgf = CompactSgf::loadFile(sgfFile);
  }
  else {
    if(boardSize == -1)
      boardSize = 19;

    string sgfData = getBenchmarkSGFData(boardSize);
    sgf = CompactSgf::parse(sgfData);
  }

  Logger logger;
  logger.setLogToStdout(true);
  logger.write("Loading model and initializing benchmark...");

  SearchParams params = Setup::loadSingleParams(cfg);
  params.maxVisits = maxVisits;
  params.maxPlayouts = maxVisits;
  params.maxTime = 1e20;
  params.searchFactorAfterOnePass = 1.0;
  params.searchFactorAfterTwoPass = 1.0;

  Setup::initializeSession(cfg);

  if(cfg.contains("nnMaxBatchSize"))
    cout << "WARNING: Your nnMaxBatchSize is hardcoded to " + cfg.getString("nnMaxBatchSize") + ", ignoring it and assuming it is >= threads, for this benchmark." << endl;

  NNEvaluator* nnEval = NULL;
  auto reallocateNNEvalWithEnoughBatchSize = [&](int maxNumThreads) {
    if(nnEval != NULL)
      delete nnEval;
    nnEval = createNNEval(maxNumThreads, sgf, modelFile, logger, cfg, params);
  };

  if(!autoTuneThreads) {
    int maxThreads = 1;
    for(int i = 0; i<numThreadsToTest.size(); i++) {
      maxThreads = std::max(maxThreads,numThreadsToTest[i]);
    }
    reallocateNNEvalWithEnoughBatchSize(maxThreads);
  }
  else
    reallocateNNEvalWithEnoughBatchSize(ternarySearchInitialMax);

  logger.write("Loaded config " + cfg.getFileName());
  logger.write("Loaded model "+ modelFile);

  cout << endl;
  cout << "Testing using " << maxVisits << " visits." << endl;
  if(maxVisits == defaultMaxVisits) {
    cout << "  If you have a good GPU, you might increase this using \"-visits N\" to get more accurate results." << endl;
    cout << "  If you have a weak GPU and this is taking forever, you can decrease it instead to finish the benchmark faster." << endl;
  }

  cout << endl;

#ifdef USE_CUDA_BACKEND
  cout << "Your GTP config is currently set to cudaUseFP16 = " << nnEval->getUsingFP16Mode().toString()
       << " and cudaUseNHWC = " << nnEval->getUsingNHWCMode().toString() << endl;
  if(nnEval->getUsingFP16Mode() == enabled_t::False)
    cout << "If you have a strong GPU capable of FP16 tensor cores (e.g. RTX2080) setting these both to true may give a large performance boost." << endl;
#endif
#ifdef USE_OPENCL_BACKEND
  cout << "You are currently using the OpenCL version of KataGo." << endl;
  cout << "If you have a strong GPU capable of FP16 tensor cores (e.g. RTX2080), "
       << "using the Cuda version of KataGo instead may give a mild performance boost." << endl;
#endif
#ifdef USE_EIGEN_BACKEND
  cout << "You are currently using the Eigen (CPU) version of KataGo. Due to having no GPU, it may be slow." << endl;
#endif
  cout << endl;
  cout << "Your GTP config is currently set to use numSearchThreads = " << params.numThreads << endl;

  vector<PlayUtils::BenchmarkResults> results;
  if(!autoTuneThreads) {
    results = doFixedTuneThreads(params,sgf,numPositionsPerGame,nnEval,logger,secondsPerGameMove,numThreadsToTest,true);
  }
  else {
    results = doAutoTuneThreads(params,sgf,numPositionsPerGame,nnEval,logger,secondsPerGameMove,reallocateNNEvalWithEnoughBatchSize);
  }

  if(numThreadsToTest.size() > 1 || autoTuneThreads) {
    PlayUtils::BenchmarkResults::printEloComparison(results,secondsPerGameMove);

    cout << "If you care about performance, you may want to edit numSearchThreads in " << cfg.getFileName() << " based on the above results!" << endl;
    if(cfg.contains("nnMaxBatchSize"))
      cout << "WARNING: Your nnMaxBatchSize is hardcoded to " + cfg.getString("nnMaxBatchSize") + ", recommend deleting it and using the default (which this benchmark assumes)" << endl;
#ifdef USE_EIGEN_BACKEND
    if(cfg.contains("numEigenThreadsPerModel")) {
      cout << "Note: Your numEigenThreadsPerModel is hardcoded to " + cfg.getString("numEigenThreadsPerModel") + ", consider deleting it and using the default (which this benchmark assumes when computing its performance stats)" << endl;
    }
#endif

    cout << "If you intend to do much longer searches, configure the seconds per game move you expect with the '-time' flag and benchmark again." << endl;
    cout << "If you intend to do short or fixed-visit searches, use lower numSearchThreads for better strength, high threads will weaken strength." << endl;

    cout << "If interested see also other notes about performance and mem usage in the top of " << cfg.getFileName() << endl;
    cout << endl;
  }

  delete nnEval;
  NeuralNet::globalCleanup();
  delete sgf;
   

  return 0;
}

static void warmStartNNEval(const CompactSgf* sgf, Logger& logger, const SearchParams& params, NNEvaluator* nnEval, Rand& seedRand) {
  Board board(sgf->xSize,sgf->ySize);
  Player nextPla = P_BLACK;
  BoardHistory hist(board,nextPla,Rules());
  SearchParams thisParams = params;
  thisParams.numThreads = 1;
  thisParams.maxVisits = 5;
  thisParams.maxPlayouts = 5;
  thisParams.maxTime = 1e20;
  AsyncBot* bot = new AsyncBot(thisParams, nnEval, &logger, Global::uint64ToString(seedRand.nextUInt64()));
  bot->setPosition(nextPla,board,hist);
  bot->genMoveSynchronous(nextPla,TimeControls());
  delete bot;
}

static NNEvaluator* createNNEval(int maxNumThreads, CompactSgf* sgf, const string& modelFile, Logger& logger, ConfigParser& cfg, const SearchParams& params) {
  int maxConcurrentEvals = maxNumThreads * 2 + 16; // * 2 + 16 just to give plenty of headroom
  int expectedConcurrentEvals = maxNumThreads;
  int defaultMaxBatchSize = std::max(8,((maxNumThreads+3)/4)*4);

  Rand seedRand;

#ifdef USE_EIGEN_BACKEND
  //For warm-starting eigen, we really don't need all that many backend threads, which are determined
  //via expectedConcurrentEvals.
  if(expectedConcurrentEvals > 2)
    expectedConcurrentEvals = 2;
#endif

  NNEvaluator* nnEval = Setup::initializeNNEvaluator(
    modelFile,modelFile,cfg,logger,seedRand,maxConcurrentEvals,expectedConcurrentEvals,
    sgf->xSize,sgf->ySize,defaultMaxBatchSize,
    Setup::SETUP_FOR_BENCHMARK
  );

  //Run on a sample position just to get any initialization and logs out of the way
  warmStartNNEval(sgf,logger,params,nnEval,seedRand);

  cout.flush();
  cerr.flush();
  //Sleep a bit to allow for nneval thread logs to finish
  std::this_thread::sleep_for(std::chrono::duration<double>(0.2));
  cout.flush();
  cerr.flush();
  cout << endl;

  return nnEval;
}

static void setNumThreads(SearchParams& params, NNEvaluator* nnEval, Logger& logger, int numThreads, const CompactSgf* sgf) {
  params.numThreads = numThreads;
#ifdef USE_EIGEN_BACKEND
  //Eigen is a little interesting in that by default, it sets numNNServerThreadsPerModel based on numSearchThreads
  //So, reset the number of threads in the nnEval each time we change the search numthreads
  logger.setLogToStdout(false);
  nnEval->killServerThreads();
  nnEval->setNumThreads(vector<int>(numThreads,-1));
  nnEval->spawnServerThreads();
  //Also since we killed and respawned all the threads, re-warm them
  Rand seedRand;
  warmStartNNEval(sgf,logger,params,nnEval,seedRand);
#else
  (void)nnEval;
  (void)logger;
  (void)numThreads;
  (void)sgf;
#endif
}

static vector<PlayUtils::BenchmarkResults> doFixedTuneThreads(
  const SearchParams& params,
  const CompactSgf* sgf,
  int numPositionsPerGame,
  NNEvaluator*& nnEval,
  Logger& logger,
  double secondsPerGameMove,
  vector<int> numThreadsToTest,
  bool printElo
) {
  vector<PlayUtils::BenchmarkResults> results;

  if(numThreadsToTest.size() > 1)
    cout << "Testing different numbers of threads: " << endl;

  for(int i = 0; i<numThreadsToTest.size(); i++) {
    const PlayUtils::BenchmarkResults* baseline = (i == 0) ? NULL : &results[0];
    SearchParams thisParams = params;
    setNumThreads(thisParams,nnEval,logger,numThreadsToTest[i],sgf);
    PlayUtils::BenchmarkResults result = PlayUtils::benchmarkSearchOnPositionsAndPrint(
      thisParams,
      sgf,
      numPositionsPerGame,
      nnEval,
      logger,
      baseline,
      secondsPerGameMove,
      printElo
    );
    results.push_back(result);
  }
  cout << endl;
  return results;
}

static vector<PlayUtils::BenchmarkResults> doAutoTuneThreads(
  const SearchParams& params,
  const CompactSgf* sgf,
  int numPositionsPerGame,
  NNEvaluator*& nnEval,
  Logger& logger,
  double secondsPerGameMove,
  std::function<void(int)> reallocateNNEvalWithEnoughBatchSize
) {
  vector<PlayUtils::BenchmarkResults> results;

  cout << "Automatically trying different numbers of threads to home in on the best: " << endl;
  cout << endl;

  map<int, PlayUtils::BenchmarkResults> resultCache; // key is threads

  auto getResult = [&](int numThreads) {
    if(resultCache.find(numThreads) == resultCache.end()) {
      const PlayUtils::BenchmarkResults* baseline = NULL;
      bool printElo = false;
      SearchParams thisParams = params;
      setNumThreads(thisParams,nnEval,logger,numThreads,sgf);
      PlayUtils::BenchmarkResults result = PlayUtils::benchmarkSearchOnPositionsAndPrint(
        thisParams,
        sgf,
        numPositionsPerGame,
        nnEval,
        logger,
        baseline,
        secondsPerGameMove,
        printElo
      );
      resultCache[numThreads] = result;
    }
    return resultCache[numThreads];
  };

  // There is a special ternary search on the integers that converges faster,
  // but since the function of threads -> elo is not perfectly convex (too noisy)
  // we will use the traditional ternary search.

  // Restrict to thread counts that are {1,2,3,4,5} * power of 2
  vector<int> possibleNumbersOfThreads;
  int twopow = 1;
  for(int i = 0; i < 20; i++) {
    // 5 * (2 ** 17) is way more than enough; 17 because we only add odd multiples to the vector, evens are just other powers of two.
    possibleNumbersOfThreads.push_back(twopow);
    possibleNumbersOfThreads.push_back(twopow * 3);
    possibleNumbersOfThreads.push_back(twopow * 5);
    twopow *= 2;
  }

  sort(possibleNumbersOfThreads.begin(), possibleNumbersOfThreads.end());

  //Adjusted for number of GPUs - it makes no sense to test low values if you have lots of GPUs
  int ternarySearchMin = nnEval->getNumGpus();
  int ternarySearchMax = (int)round(ternarySearchInitialMax * 0.5 * (1 + nnEval->getNumGpus()));
  if(ternarySearchMax < ternarySearchMin * 4)
    ternarySearchMax = ternarySearchMin * 4;

  while(true) {
    reallocateNNEvalWithEnoughBatchSize(ternarySearchMax);
    cout << endl;

    int start = 0;
    int end = possibleNumbersOfThreads.size()-1;
    for(int i = 0; i < possibleNumbersOfThreads.size(); i++) {
      if(possibleNumbersOfThreads[i] < ternarySearchMin) {
        start = i + 1;
      }
      if(possibleNumbersOfThreads[i] > ternarySearchMax) {
        end = i - 1;
        break;
      }
    }
    if(start > end)
      start = end;

    cout << "Possible numbers of threads to test: ";
    for(int i = start; i <= end; i++) {
      cout << possibleNumbersOfThreads[i] << ", ";
    }
    cout << endl;
    cout << endl;

    while(start <= end) {
      int firstMid = start + (end - start) / 3;
      int secondMid = end - (end - start) / 3;

      double effect1 = getResult(possibleNumbersOfThreads[firstMid]).computeEloEffect(secondsPerGameMove);
      double effect2 = getResult(possibleNumbersOfThreads[secondMid]).computeEloEffect(secondsPerGameMove);
      if(effect1 < effect2)
        start = firstMid + 1;
      else
        end = secondMid - 1;
    }

    double bestElo = 0;
    int bestThreads = 0;

    results.clear();
    for(auto it : resultCache) {
      PlayUtils::BenchmarkResults result = it.second;
      double elo = result.computeEloEffect(secondsPerGameMove);
      results.push_back(result);

      if(elo > bestElo) {
        bestThreads = result.numThreads;
        bestElo = elo;
      }
    }

    //If our optimal thread count is in the top 2/3 of the maximum search limit, increase the search limit and repeat.
    if(3 * bestThreads > 2 * ternarySearchMax && ternarySearchMax < 5000) {
      ternarySearchMin = ternarySearchMax / 2;
      ternarySearchMax = ternarySearchMax * 2 + 32;
      cout << endl << endl << "Optimal number of threads is fairly high, increasing the search limit and trying again." << endl << endl;
      continue;
    }
    else {
      cout << endl << endl << "Ordered summary of results: " << endl << endl;
      for(int i = 0; i<results.size(); i++) {
        cout << results[i].toStringWithElo(i == 0 ? NULL : &results[0], secondsPerGameMove) << endl;
      }
      cout << endl;
      break;
    }
  }

  return results;
}


int MainCmds::genconfig(int argc, const char* const* argv, const char* firstCommand) {
  Board::initHash();
   

  string outputFile;
  string modelFile;
  bool modelFileIsDefault;
  try {
    KataGoCommandLine cmd("Automatically generate and tune a new GTP config.");
    cmd.addModelFileArg();

    TCLAP::ValueArg<string> outputFileArg("","output","Path to write new config (default gtp.cfg)",false,string("gtp.cfg"),"FILE");
    cmd.add(outputFileArg);
    cmd.parse(argc,argv);

    outputFile = outputFileArg.getValue();
    modelFile = cmd.getModelFile();
    modelFileIsDefault = cmd.modelFileIsDefault();
  }
  catch (TCLAP::ArgException &e) {
    cerr << "Error: " << e.error() << " for argument " << e.argId() << endl;
    return 1;
  }

  auto promptAndParseInput = [](const string& prompt, std::function<void(const string&)> parse) {
    while(true) {
      try {
        cout << prompt << std::flush;
        string line;
        if(std::getline(std::cin, line)) {
          parse(Global::trim(line));
          break;
        }
        else {
          break;
        }
      }
      catch(const StringError& err) {
        string what = err.what();
        what = Global::trim(what);
        if(what.length() > 0)
          cout << err.what() << endl;
      }
    }
    if(!std::cin) {
      throw StringError("Stdin was closed - failing and not generating a config");
    }
  };

  auto parseYN = [](const string& line, bool& b) {
    string s = Global::toLower(Global::trim(line));
    if(s == "yes" || s == "y")
      b = true;
    else if(s == "no" || s == "n")
      b = false;
    else
      throw StringError("Please answer y or n");
  };

  if(gfs::exists(gfs::path(outputFile))) {
    bool b = false;
    promptAndParseInput("File " + outputFile + " already exists, okay to overwrite it with an entirely new config (y/n)?\n", [&](const string& line) { parseYN(line,b); });
    if(!b) {
      cout << "Please provide an alternate file path to output the generated config to via '-output NEWFILEPATH'" << endl;
      return 0;
    }
  }

  int boardSize = 19;
  string sgfData =getBenchmarkSGFData(boardSize);
  CompactSgf* sgf = CompactSgf::parse(sgfData);

  Rules configRules;
  int64_t configMaxVisits = ((int64_t)1) << 50;
  int64_t configMaxPlayouts = ((int64_t)1) << 50;
  double configMaxTime = 1e20;
  double configMaxPonderTime = -1.0;
  vector<int> configDeviceIdxs;
  int configNNCacheSizePowerOfTwo = 20;
  int configNNMutexPoolSizePowerOfTwo = 16;
  int configNumSearchThreads = 6;

  cout << endl;
  cout << "=========================================================================" << endl;
  cout << "RULES" << endl;

  {
    cout << endl;
    string prompt =
      "What rules should KataGo use by default for play and analysis?\n"
      "(chinese, japanese, korean, tromp-taylor, aga, chinese-ogs, new-zealand, bga, stone-scoring, aga-button):\n";
    promptAndParseInput(prompt, [&](const string& line) { configRules = Rules::parseRules(line); });
  }

  cout << endl;
  cout << "=========================================================================" << endl;
  cout << "SEARCH LIMITS" << endl;

  bool useSearchLimit = false;
  {
    cout << endl;
    string prompt =
      "When playing games, KataGo will always obey the time controls given by the GUI/tournament/match/online server.\n"
      "But you can specify an additional limit to make KataGo move much faster. This does NOT affect analysis/review,\n"
      "only affects playing games. Add a limit? (y/n) (default n):\n";
    promptAndParseInput(prompt, [&](const string& line) {
        if(line == "") useSearchLimit = false;
        else parseYN(line,useSearchLimit);
      });
  }

  if(!useSearchLimit) {
    cout << endl;
    string prompt =
      "NOTE: No limits configured for KataGo. KataGo will obey time controls provided by the GUI or server or match script\n"
      "but if they don't specify any, when playing games KataGo may think forever without moving. (press enter to continue)\n";
    promptAndParseInput(prompt, [&](const string& line) {
        (void)line;
      });
  }

  else {
    string whatLimit = "";
    {
      cout << endl;
      string prompt =
        "What to limit per move? Visits, playouts, or seconds?:\n";
      promptAndParseInput(prompt, [&](const string& line) {
          string s = Global::toLower(line);
          if(s == "visits" || s == "playouts" || s == "seconds") whatLimit = s;
          else if(s == "visit") whatLimit = "visits";
          else if(s == "playout") whatLimit = "playouts";
          else if(s == "second") whatLimit = "seconds";
          else throw StringError("Please specify one of \"visits\" or \"playouts\" or '\"seconds\"");
        });
    }

    if(whatLimit == "visits") {
      cout << endl;
      string prompt =
        "Specify max number of visits/move when playing games (doesn't affect analysis), leave blank for default (500):\n";
      promptAndParseInput(prompt, [&](const string& line) {
          if(line == "") configMaxVisits = 500;
          else {
            configMaxVisits = Global::stringToInt64(line);
            if(configMaxVisits < 1 || configMaxVisits > 1000000000)
              throw StringError("Must be between 1 and 1000000000");
          }
        });
    }
    else if(whatLimit == "playouts") {
      cout << endl;
      string prompt =
        "Specify max number of playouts/move when playing games (doesn't affect analysis), leave blank for default (300):\n";
      promptAndParseInput(prompt, [&](const string& line) {
          if(line == "") configMaxPlayouts = 300;
          else {
            configMaxPlayouts = Global::stringToInt64(line);
            if(configMaxPlayouts < 1 || configMaxPlayouts > 1000000000)
              throw StringError("Must be between 1 and 1000000000");
          }
        });
    }
    else if(whatLimit == "seconds") {
      cout << endl;
      string prompt =
        "Specify max time/move in seconds when playing games (doesn't affect analysis). Leave blank for default (10):\n";
      promptAndParseInput(prompt, [&](const string& line) {
          if(line == "") configMaxTime = 10.0;
          else {
            configMaxTime = Global::stringToDouble(line);
            if(isnan(configMaxTime) || configMaxTime <= 0 || configMaxTime >= 1.0e20)
              throw StringError("Must positive and less than 1e20");
          }
        });
    }
  }

  bool usePonder = false;
  {
    cout << endl;
    string prompt =
      "When playing games, KataGo can optionally ponder during the opponent's turn. This gives faster/stronger play\n"
      "in real games but should NOT be enabled if you are running tests with fixed limits (pondering may exceed those\n"
      "limits), or to avoid stealing the opponent's compute time when testing two bots on the same machine.\n"
      "Enable pondering? (y/n, default n):";
    promptAndParseInput(prompt, [&](const string& line) {
        if(line == "") usePonder = false;
        else parseYN(line,usePonder);
      });
  }

  if(usePonder) {
    cout << endl;
    string prompt =
      "Specify max num seconds KataGo should ponder during the opponent's turn. Leave blank for no limit:\n";
    promptAndParseInput(prompt, [&](const string& line) {
        if(line == "") configMaxPonderTime = 1.0e20;
        else {
          configMaxPonderTime = Global::stringToDouble(line);
          if(isnan(configMaxPonderTime) || configMaxPonderTime <= 0 || configMaxPonderTime >= 1.0e20)
            throw StringError("Must positive and less than 1e20");
        }
      });
  }

  cout << endl;
  cout << "=========================================================================" << endl;
  cout << "GPUS AND RAM" << endl;

#ifndef USE_EIGEN_BACKEND
  {
    cout << endl;
    cout << "Finding available GPU-like devices..." << endl;
    NeuralNet::printDevices();
    cout << endl;

    string prompt =
      "Specify devices/GPUs to use (for example \"0,1,2\" to use devices 0, 1, and 2). Leave blank for a default SINGLE-GPU config:\n";
    promptAndParseInput(prompt, [&](const string& line) {
        vector<string> pieces = Global::split(line,',');
        configDeviceIdxs.clear();
        for(size_t i = 0; i<pieces.size(); i++) {
          string piece = Global::trim(pieces[i]);
          int idx = Global::stringToInt(piece);
          if(idx < 0 || idx > 10000)
            throw StringError("Invalid device idx: " + Global::intToString(idx));
          configDeviceIdxs.push_back(idx);
        }
      });
  }
#endif

  {
    cout << endl;
    string prompt =
      "By default, KataGo will cache up to about 3GB of positions in memory (RAM), in addition to\n"
      "whatever the current search is using. Specify a different max in GB or leave blank for default:\n";
    promptAndParseInput(prompt, [&](const string& line) {
        string s = Global::toLower(line);
        if(Global::isSuffix(s,"gb"))
          s = s.substr(0,s.length()-2);
        s = Global::trim(s);
        double approxGBLimit;
        if(s == "") approxGBLimit = 3.0;
        else {
          approxGBLimit = Global::stringToDouble(s);
          if(isnan(approxGBLimit) || approxGBLimit <= 0 || approxGBLimit >= 1000000.0)
            throw StringError("Must positive and less than 1000000");
        }
        approxGBLimit *= 1.00001;
        configNNCacheSizePowerOfTwo = 10; //Never set below this size
        while(configNNCacheSizePowerOfTwo < 48) {
          double memUsage = pow(2.0, configNNCacheSizePowerOfTwo) * 3000.0;
          if(memUsage * 2.0 > approxGBLimit * 1073741824.0)
            break;
          configNNCacheSizePowerOfTwo += 1;
        }
        configNNMutexPoolSizePowerOfTwo = configNNCacheSizePowerOfTwo - 4;
        if(configNNMutexPoolSizePowerOfTwo < 10)
          configNNMutexPoolSizePowerOfTwo = 10;
        if(configNNMutexPoolSizePowerOfTwo > 24)
          configNNMutexPoolSizePowerOfTwo = 24;
      });
  }

  cout << endl;
  cout << "=========================================================================" << endl;
  cout << "PERFORMANCE TUNING" << endl;

  bool skipThreadTuning = false;
  if(gfs::exists(gfs::path(outputFile))) {
    int oldConfigNumSearchThreads = -1;
    try {
      ConfigParser oldCfg(outputFile);
      oldConfigNumSearchThreads = oldCfg.getInt("numSearchThreads",1,4096);
    }
    catch(const StringError&) {
      cout << "NOTE: Overwritten config does not specify numSearchThreads or otherwise could not be parsed." << endl;
      cout << "Beginning performance tuning to set this." << endl;
    }
    if(oldConfigNumSearchThreads > 0) {
      promptAndParseInput(
        "Actually " + outputFile + " already exists, can skip performance tuning if desired and just use\nthe number of threads (" +
        Global::intToString(oldConfigNumSearchThreads) + ") "
        "already in that config (all other settings will still be overwritten).\nSkip performance tuning (y/n)?\n",
        [&](const string& line) { parseYN(line,skipThreadTuning); }
      );
      if(skipThreadTuning) {
        configNumSearchThreads = oldConfigNumSearchThreads;
      }
    }
  }

  string configFileContents;
  auto updateConfigContents = [&]() {
    configFileContents = GTPConfig::makeConfig(
      configRules,
      configMaxVisits,
      configMaxPlayouts,
      configMaxTime,
      configMaxPonderTime,
      configDeviceIdxs,
      configNNCacheSizePowerOfTwo,
      configNNMutexPoolSizePowerOfTwo,
      configNumSearchThreads
    );
  };
  updateConfigContents();

  if(!skipThreadTuning) {
    int64_t maxVisitsFromUser = -1;
    double secondsPerGameMove = defaultSecondsPerGameMove;
    {
      cout << endl;
      string prompt =
        "Specify number of visits to use test/tune performance with, leave blank for default based on GPU speed.\n"
        "Use large number for more accurate results, small if your GPU is old and this is taking forever:\n";
      promptAndParseInput(prompt, [&](const string& line) {
          if(line == "") maxVisitsFromUser = -1;
          else {
            maxVisitsFromUser = Global::stringToInt64(line);
            if(maxVisitsFromUser < 1 || maxVisitsFromUser > 1000000000)
              throw StringError("Must be between 1 and 1000000000");
          }
        });
    }

    {
      cout << endl;
      string prompt =
        "Specify number of seconds/move to optimize performance for (default " + Global::doubleToString(defaultSecondsPerGameMove) + "), leave blank for default:\n";
      promptAndParseInput(prompt, [&](const string& line) {
          if(line == "") secondsPerGameMove = defaultSecondsPerGameMove;
          else {
            secondsPerGameMove = Global::stringToDouble(line);
            if(isnan(secondsPerGameMove) || secondsPerGameMove <= 0 || secondsPerGameMove > 1000000)
              throw StringError("Must be between 0 and 1000000");
          }
        });
    }

    istringstream inConfig(configFileContents);
    ConfigParser cfg(inConfig);

    Logger logger;
    logger.setLogToStdout(true);
    logger.write("Loading model and initializing benchmark...");

    SearchParams params = Setup::loadSingleParams(cfg);
    params.maxVisits = defaultMaxVisits;
    params.maxPlayouts = defaultMaxVisits;
    params.maxTime = 1e20;
    params.searchFactorAfterOnePass = 1.0;
    params.searchFactorAfterTwoPass = 1.0;

    Setup::initializeSession(cfg);

    NNEvaluator* nnEval = NULL;
    auto reallocateNNEvalWithEnoughBatchSize = [&](int maxNumThreads) {
      if(nnEval != NULL)
        delete nnEval;
      nnEval = createNNEval(maxNumThreads, sgf, modelFile, logger, cfg, params);
    };
    cout << endl;

    int64_t maxVisits;
    if(maxVisitsFromUser > 0) {
      maxVisits = maxVisitsFromUser;
    }
    else {
      cout << "Running quick initial benchmark at 16 threads!" << endl;
      vector<int> numThreads = {16};
      reallocateNNEvalWithEnoughBatchSize(ternarySearchInitialMax);
      vector<PlayUtils::BenchmarkResults> results = doFixedTuneThreads(params,sgf,3,nnEval,logger,secondsPerGameMove,numThreads,false);
      double visitsPerSecond = results[0].totalVisits / (results[0].totalSeconds + 0.00001);
      //Make tests use about 2 seconds each
      maxVisits = (int64_t)round(2.0 * visitsPerSecond/100.0) * 100;
      if(maxVisits < 200) maxVisits = 200;
      if(maxVisits > 10000) maxVisits = 10000;
    }

    params.maxVisits = maxVisits;
    params.maxPlayouts = maxVisits;

    const int numPositionsPerGame = 10;

    cout << "=========================================================================" << endl;
    cout << "TUNING NOW" << endl;
    cout << "Tuning using " << maxVisits << " visits." << endl;

    vector<PlayUtils::BenchmarkResults> results;
    results = doAutoTuneThreads(params,sgf,numPositionsPerGame,nnEval,logger,secondsPerGameMove,reallocateNNEvalWithEnoughBatchSize);

    PlayUtils::BenchmarkResults::printEloComparison(results,secondsPerGameMove);
    int bestIdx = 0;
    for(int i = 1; i<results.size(); i++) {
      if(results[i].computeEloEffect(secondsPerGameMove) > results[bestIdx].computeEloEffect(secondsPerGameMove))
        bestIdx = i;
    }
    cout << "Using " << results[bestIdx].numThreads << " numSearchThreads!" << endl;

    configNumSearchThreads = results[bestIdx].numThreads;

    delete nnEval;
  }

  updateConfigContents();

  cout << endl;
  cout << "=========================================================================" << endl;
  cout << "DONE" << endl;
  cout << endl;
  cout << "Writing new config file to " << outputFile << endl;
  ofstream out(outputFile, ofstream::out | ofstream::trunc);
  out << configFileContents;
  out.close();

  cout << "You should be now able to run KataGo with this config via something like:" << endl;
  if(modelFileIsDefault)
    cout << firstCommand << " gtp -config '" << outputFile << "'" << endl;
  else
    cout << firstCommand << " gtp -model '" << modelFile << "' -config '" << outputFile << "'" << endl;
  cout << endl;

  cout << "Feel free to look at and edit the above config file further by hand in a txt editor." << endl;
  cout << "For more detailed notes about performance and what options in the config do, see:" << endl;
  cout << "https://github.com/lightvector/KataGo/blob/master/cpp/configs/gtp_example.cfg" << endl;
  cout << endl;

  NeuralNet::globalCleanup();
  delete sgf;
   

  return 0;
}
