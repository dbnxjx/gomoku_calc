/*
 *  Rapfi, a Gomoku/Renju playing engine supporting piskvork protocol.
 *  Copyright (C) 2022  Rapfi developers
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

#include "config.h"

const std::string Config::InternalConfig = R"internalConfig(
[requirement]
min_version = [0,34,2]

[model]
[model.eval]
model_type = 2
table2 = [
0,	0,	0,	0,	0,	0,	0,	0,	0,	0,	0,	0,	0,	0,	36,	41,
	0,	0,	0,	0,	0,	0,	0,	0,	0,	0,	0,	0,	0,	36,	41,
		0,	0,	0,	1,	1,	1,	2,	2,	9,	9,	16,	16,	36,	41,
			0,	0,	1,	1,	1,	2,	2,	10,	10,	17,	17,	38,	43,
				1,	2,	2,	2,	3,	3,	11,	11,	19,	19,	39,	44,
					3,	3,	3,	4,	4,	13,	13,	20,	20,	42,	47,
						3,	3,	4,	4,	13,	13,	20,	20,	42,	47,
							3,	4,	4,	13,	13,	20,	20,	42,	47,
								5,	5,	14,	14,	22,	22,	43,	49,
									5,	14,	14,	22,	22,	43,	49,
										25,	25,	34,	34,	57,	62,
											25,	34,	34,	57,	62,
												43,	43,	67,	72,
													43,	67,	72,
														93,	99,
															105
]

[model.eval.renju]
model_type = 2
table2 = [
0,	0,	0,	0,	0,	1,	1,	1,	2,	2,	9,	9,	15,	15,	36,	40,
	0,	0,	0,	0,	1,	1,	1,	2,	2,	9,	9,	15,	15,	36,	40,
		0,	0,	0,	1,	1,	1,	2,	2,	9,	9,	15,	15,	36,	40,
			0,	0,	1,	1,	1,	2,	2,	9,	9,	15,	15,	37,	40,
				1,	2,	2,	2,	3,	3,	10,	10,	17,	17,	38,	42,
					3,	3,	3,	4,	4,	12,	12,	18,	18,	40,	44,
						3,	3,	4,	4,	12,	12,	18,	18,	40,	44,
							3,	4,	4,	12,	12,	18,	18,	40,	44,
								5,	5,	13,	13,	20,	20,	42,	46,
									5,	13,	13,	20,	20,	42,	46,
										23,	23,	30,	30,	54,	58,
											23,	30,	30,	54,	58,
												38,	38,	63,	67,
													38,	63,	67,
														90,	94,
															99
]

[model.score]
[model.score.self]
model_type = 1
table1 = [-1,0,0,1,2,6,6,6,9,9,16,20,30,30,100,500]
table1_scale = 2.0

[model.score.oppo]
model_type = 1
table1 = [-1,0,0,1,2,6,6,6,9,9,16,20,30,30,100,500]
table1_scale = 1.0

# ---------------------------------------------------------------------------
# Yixinboard-compatible analysis database (cross-session result cache).
# 跨会话分析结果数据库（Yixinboard/yixindb 格式）。
# The Android app points the engine at a writable path with "YXSETDATABASE".
# enable_by_default stays false so no relative "rapfi.db" is opened up front.
# ---------------------------------------------------------------------------
[database]
type = "yixindb"
enable_by_default = false
cache_size = 4096
record_cache_size = 32768

[database.yixindb]
compressed_save = true
save_on_close = true
num_backups_on_save = 1

# Save restrictions: only reliable results are stored, not every search step.
# 保存限制：不是每一步结果都入库，只保留可靠结果。
[database.search]
# Read-only mode: keep reading cached results, stop writing new ones.
# 只读模式：仍读缓存、不再写入
readonly_mode = false

# Query floor / 数据库最少查询层数
query_ply = 3
pv_iter_per_ply_increment = 1
nonpv_iter_per_ply_increment = 2

# PV writes only at the root line / 主要分支下数据库最大写入层数
pv_write_ply = 0
# Minimum search depth for a PV record / 主要分支允许写入的最低搜索深度
pv_write_min_depth = 10
nonpv_write_ply = 0
nonpv_write_min_depth = 10

# Absolute floor for ANY record, including the root node. The root otherwise
# bypasses the per-branch gates above and would store shallow first iterations.
# 任意记录（含根节点）的最低搜索深度。
min_write_depth = 10

# Value range allowed to write / 数据库允许写入的最大分数范围
write_value_range = 800

# Mate (forced win/loss) records / 必胜必败记录
mate_write_ply = 2
# Exact (resolved) mate needs this depth / 已算清（准确步数）的必杀最低深度
mate_write_min_depth_exact = 10
# Unresolved mate needs this depth; set very large to skip such records entirely.
# 未算清（非准确步数）的必杀最低深度，取很大值即可完全不写入这类记录
mate_write_min_depth_nonexact = 100
# Mate shorter than this many steps is not recorded / 少于该步数的必胜必败不记录
mate_write_min_step = 2

# Overwrite policy when a record already exists / 已存在记录的覆盖策略
exact_overwrite_ply = 100
nonexact_overwrite_ply = 0
overwrite_rule = "better_value_depth_bound"
overwrite_exact_bias = 4
overwrite_depth_bound_bias = -1
query_result_depth_bound_bias = 0
)internalConfig";
