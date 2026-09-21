#ifndef SEARCH_TIMECONTROLS_H
#define SEARCH_TIMECONTROLS_H

#include "../core/global.h"
#include "../game/board.h"
#include "../game/boardhistory.h"

struct TimeControls {

  double perMoveTime;

  double mainTimeLeft;
  //Construct a TimeControls with unlimited main time and otherwise zero initialized.
  TimeControls();
  ~TimeControls();


  //minTime - if you use less than this, you are wasting time that will not be reclaimed
  //recommendedTime - recommended mean time to search
  //maxTime - very bad to go over this time, possibly immediately losing
  void getTime(const Board& board, const BoardHistory& hist, double lagBuffer, double& minTime, double& recommendedTime, double& maxTime) const;

  std::string toDebugString() const;
  std::string toDebugString(const Board& board, const BoardHistory& hist, double lagBuffer) const;
};

#endif  // SEARCH_TIMECONTROLS_H_
