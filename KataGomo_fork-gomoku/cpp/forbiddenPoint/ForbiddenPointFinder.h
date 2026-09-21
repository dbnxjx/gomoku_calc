#if !defined(FORBIDDENPOINTFINDER_H_INCLUDED_)
#define FORBIDDENPOINTFINDER_H_INCLUDED_



#include <stdint.h>
#include "../core/global.h"
//#if _MSC_VER > 1000
//#pragma once

//Player
typedef int8_t Player;
static constexpr Player P_BLACK = 1;
static constexpr Player P_WHITE = 2;

//Color of a point on the board
typedef int8_t Color;
static constexpr Color C_EMPTY = 0;
static constexpr Color C_BLACK = 1;
static constexpr Color C_WHITE = 2;
static constexpr Color C_WALL = 3;
static constexpr int NUM_BOARD_COLORS = 4;

#define BLACKFIVE 24
#define WHITEFIVE 25
#define BLACKFORBIDDEN 26
#if RULE==RENJU
class CForbiddenPointFinder
{
public:
	//int	nForbiddenPoints;
//	CPoint ptForbidden[f_boardsize * f_boardsize];

	int f_boardsize;// = 15;
private:

public:
	char cBoard[MAX_FLEN + 2][MAX_FLEN + 2];
	CForbiddenPointFinder();
	CForbiddenPointFinder(int size);
	virtual ~CForbiddenPointFinder();

	void Clear();
	int  AddStone(int x, int y, char cStone);
	bool isForbidden(int x, int y);
	bool isForbiddenNoNearbyCheck(int x, int y);
	void SetStone(int x, int y, char cStone);
	//void SetStone(int x, int y, char cStone);
	bool IsFive(int x, int y, int nColor);
	bool IsOverline(int x, int y);
	bool IsFive(int x, int y, int nColor, int nDir);
	bool IsFour(int x, int y, int nColor, int nDir);
	int  IsOpenFour(int x, int y, int nColor, int nDir);
	bool IsOpenThree(int x, int y, int nColor, int nDir	);
	bool IsDoubleFour(int x, int y);
	bool IsDoubleThree(int x, int y);

	//void FindForbiddenPoints();
};
#endif//RENJU
#endif // !defined(FORBIDDENPOINTFINDER_H_INCLUDED_)