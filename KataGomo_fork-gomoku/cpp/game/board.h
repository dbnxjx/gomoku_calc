/*
 * board.h
 * Originally from an unreleased project back in 2010, modified since.
 * Authors: brettharrison (original), David Wu (original and later modifications).
 */

#ifndef GAME_BOARD_H_
#define GAME_BOARD_H_

#include "../core/global.h"
#include "../core/hash.h"
#include "../forbiddenPoint/ForbiddenPointFinder.h"

#ifndef COMPILE_MAX_BOARD_LEN
#define COMPILE_MAX_BOARD_LEN MAX_FLEN
#endif

//TYPES AND CONSTANTS-----------------------------------------------------------------

struct Board;


typedef char MovePriority;
static const MovePriority MP_NORMAL = 126;
static const MovePriority MP_FIVE = 1;
static const MovePriority MP_OPPOFOUR = 2;
static const MovePriority MP_MYLIFEFOUR = 3;
static const MovePriority MP_VCF = 4;
static const MovePriority MP_USELESS = 127;
static const MovePriority MP_ILLEGAL = -1;

static inline Color getOpp(Color c)
{return c ^ 3;}

//Conversions for players and colors
namespace PlayerIO {
  char colorToChar(Color c);
  std::string playerToStringShort(Player p);
  std::string playerToString(Player p);
  bool tryParsePlayer(const std::string& s, Player& pla);
  Player parsePlayer(const std::string& s);
}

//Location of a point on the board
//(x,y) is represented as (x+1) + (y+1)*(x_size+1)
typedef short Loc;
namespace Location
{
  Loc getLoc(int x, int y, int x_size);
  int getX(Loc loc, int x_size);
  int getY(Loc loc, int x_size);

  void getAdjacentOffsets(short adj_offsets[8], int x_size);
  bool isAdjacent(Loc loc0, Loc loc1, int x_size);
  Loc getMirrorLoc(Loc loc, int x_size, int y_size);
  Loc getCenterLoc(int x_size, int y_size);
  bool isCentral(Loc loc, int x_size, int y_size);
  int distance(Loc loc0, Loc loc1, int x_size);
  int euclideanDistanceSquared(Loc loc0, Loc loc1, int x_size);

  std::string toString(Loc loc, int x_size, int y_size);
  std::string toString(Loc loc, const Board& b);
  std::string toStringMach(Loc loc, int x_size);
  std::string toStringMach(Loc loc, const Board& b);

  bool tryOfString(const std::string& str, int x_size, int y_size, Loc& result);
  bool tryOfString(const std::string& str, const Board& b, Loc& result);
  Loc ofString(const std::string& str, int x_size, int y_size);
  Loc ofString(const std::string& str, const Board& b);

  std::vector<Loc> parseSequence(const std::string& str, const Board& b);
}

//Simple structure for storing moves. Not used below, but this is a convenient place to define it.
STRUCT_NAMED_PAIR(Loc,loc,Player,pla,Move);

//Fast lightweight board designed for playouts and simulations, where speed is essential.
//Simple ko rule only.
//Does not enforce player turn order.

struct Board
{
	//Initialization------------------------------
	//Initialize the zobrist hash.
	//MUST BE CALLED AT PROGRAM START!
	static void initHash();

	//Board parameters and Constants----------------------------------------

	static const int MAX_LEN = COMPILE_MAX_BOARD_LEN;  //Maximum edge length allowed for the board
	static const int MAX_PLAY_SIZE = MAX_LEN * MAX_LEN;  //Maximum number of playable spaces
	static const int MAX_ARR_SIZE = (MAX_LEN + 1)*(MAX_LEN + 2) + 1; //Maximum size of arrays needed

	//Location used to indicate an invalid spot on the board.
	static const Loc NULL_LOC = 0;
	//Location used to indicate a pass move is desired.
	static const Loc PASS_LOC = 1;

	//Zobrist Hashing------------------------------
	static bool IS_ZOBRIST_INITALIZED;
	static Hash128 ZOBRIST_SIZE_X_HASH[MAX_LEN + 1];
	static Hash128 ZOBRIST_SIZE_Y_HASH[MAX_LEN + 1];
	static Hash128 ZOBRIST_BOARD_HASH[MAX_ARR_SIZE][4];
	static Hash128 ZOBRIST_PLAYER_HASH[4];
	static const Hash128 ZOBRIST_GAME_IS_OVER;



	//Constructors---------------------------------
	Board();  //Create Board of size (19,19)
	Board(int x, int y); //Create Board of size (x,y)
	Board(const Board& other);

	Board& operator=(const Board&) = default;

	//Functions------------------------------------

	//Check if this location is on the board
	bool isOnBoard(Loc loc) const;
	//Is this board empty?
	bool isEmpty() const;


  bool isForbidden(Loc loc) const;
  MovePriority getMovePriority(Player pla, Loc loc, bool isSixWin, bool isPassForbidded)const;
  MovePriority getMovePriorityAssumeLegal(Player pla, Loc loc, bool isSixWin)const;
private:
  MovePriority getMovePriorityOneDirectionAssumeLegal(Player pla, Loc loc, bool isSixWin, int adjID)const;
  int connectionLengthOneDirection(Player pla, Loc loc, short adj, bool isSixWin, bool& isLife)const;
public:

  //Check if moving here is legal.
  bool isLegal(Loc loc, Player pla, bool isStrict) const;



	//Sets the specified stone if possible. Returns true usually, returns false location or color were out of range.
	bool setStone(Loc loc, Color color);


	//Plays the specified move, assuming it is legal.
	void playMoveAssumeLegal(Loc loc, Player pla);

	//Get what the position hash would be if we were to play this move and resolve captures and suicides.
	//Assumes the move is on an empty location.
	Hash128 getPosHashAfterMove(Loc loc, Player pla) const;

	//Run some basic sanity checks on the board state, throws an exception if not consistent, for testing/debugging
	void checkConsistency() const;

	static Board parseBoard(int xSize, int ySize, const std::string& s);
	static Board parseBoard(int xSize, int ySize, const std::string& s, char lineDelimiter);
	static void printBoard(std::ostream& out, const Board& board, Loc markLoc, const std::vector<Move>* hist);
	static std::string toStringSimple(const Board& board, char lineDelimiter);

	//Data--------------------------------------------

	int x_size;                  //Horizontal size of board
	int y_size;                  //Vertical size of board
	Color colors[MAX_ARR_SIZE];  //Color of each location on the board.

  int movenum;

	Hash128 pos_hash; //A zobrist hash of the current board position (does not include ko point or player to move)
	short adj_offsets[8]; //Indices 0-3: Offsets to add for adjacent points. Indices 4-7: Offsets for diagonal points. 2 and 3 are +x and +y.

private:
	void init(int xS, int yS);

	friend std::ostream& operator<<(std::ostream& out, const Board& board);




};
#endif // GAME_BOARD_H_
