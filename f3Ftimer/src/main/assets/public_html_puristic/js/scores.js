function scores(){
	var list = document.createElement('div');
	$(list).addClass('scores list');

	return list;
}

function find_pilot_id(currentValue) {
    return currentValue.pilot_id == this;
}

function compareTotalPoints(a, b) {
    return b.points - a.points;
}

function compareSubtotalPoints(a, b) {
    if (b.total - a.total === 0) {
        var sum_discards_b = 0;
        var d;
        for (d = 0 ; d < b.discards.length; d+=1) {
            sum_discards_b += b.discards[d].points;
        }
        var sum_discards_a = 0;
        for (d = 0 ; d < b.discards.length; d+=1) {
            sum_discards_a += a.discards[d].points;
        }
        if (sum_discards_b - sum_discards_a === 0) {
            return b.penalties - a.penalties;
        } else {
            return sum_discards_b - sum_discards_a;
        }
    } else {
        return b.total-a.total;
    }
}

function subTotals(last_round) {
	var pilot_scores = [];
    var sub_totals = [];

	var first_discard_round = 4;
	var second_discard_round = 15;

	var sub_num_discards = (last_round>=first_discard_round) ? ((last_round>=second_discard_round) ? 2 : 1) : 0;

	var pilots_index_map = new Map();
	var i;
	for (i=0; i < model.pilots.length; i+=1) {
		pilots_index_map.set(model.pilots[i].id, i);
	}

	// create pilot score array with time and points and subtotals array
	var group_size;
	var group_index;
	var pilot_index;
	var round;
	var pilot_id;
    var pilot_penalty;
    var pilot_time;
    var pilot_points;
    var pilot_status;
    var pilot_flown;
    var pilots_pilot_index;
    var pilot_name;
    var pilot_team;
	for (round = 0; round<last_round; round+=1){
	    pilot_scores[round] = [];
        for (group_index = 0; group_index < model.racetimes[round].length; group_index+=1){
            group_size = model.racetimes[round][group_index].length;
            for (pilot_index=0; pilot_index < model.racetimes[round][group_index].length; pilot_index+=1){
                pilot_id = model.racetimes[round][group_index][pilot_index].id;
                pilot_penalty = model.racetimes[round][group_index][pilot_index].penalty * 100;
                pilot_time = model.racetimes[round][group_index][pilot_index].time;
                pilot_points = parseFloat(model.racetimes[round][group_index][pilot_index].points);
			    pilot_status = model.pilots[pilots_index_map.get(pilot_id)].status;
                pilot_flown = model.racetimes[round][group_index][pilot_index].flown;
			    /* nullify all scores that should be ignored */
			    if (pilot_status == "4" || pilot_flown == "0") {
					pilot_penalty = "0";
					pilot_time = "0.00";
					pilot_points = "0.00";
				}

                pilot_scores[round].push({pilot_id:pilot_id, penalty:pilot_penalty, points:pilot_points, flown:pilot_flown, status:pilot_status});

                if (undefined === sub_totals.find(find_pilot_id, pilot_id)){
                    pilots_pilot_index = pilots_index_map.get(String(pilot_id));
                    pilot_name = (model.pilots[pilots_pilot_index].firstname + " " + model.pilots[pilots_pilot_index].lastname);
                    pilot_name = pilot_name.trim();
                    pilot_team = model.pilots[pilots_pilot_index].team;
                    sub_totals.push({rank:0, pilot_id:pilot_id, pilot_name:pilot_name, team:pilot_team, total:0, penalties:0, percent:0, discards:[]});
                }
            }
        }
    }

    // calculate total score
    var pilot_discard_count;
    var pilot_penalties;
    var tmp_pilot_points;
    var tot;
    var pp;
    var j;
    for (i=0; i<sub_totals.length; i+=1){
        // Sort the individual pilot's round, so that discards can be removed
        pilot_penalties = 0;
        tmp_pilot_points = [];

        for (round = 0; round<last_round; round+=1){
            for (j=0; j<pilot_scores[round].length; j+=1){
                if (pilot_scores[round][j].pilot_id == sub_totals[i].pilot_id){
                    tmp_pilot_points.push({pilot_id:sub_totals[i].pilot_id, round_id:round, points:pilot_scores[round][j].points});
                    pilot_penalties += parseInt(pilot_scores[round][j].penalty);
                    break;
                }
            }
        }
        tmp_pilot_points.sort(compareTotalPoints);

        // Remove discards
        for (pilot_discard_count = sub_num_discards; pilot_discard_count>0; pilot_discard_count-=1){
            if (pilot_discard_count === 2 && tmp_pilot_points.length >= second_discard_round){
                sub_totals[i].discards.push(tmp_pilot_points.pop());
            } else if (pilot_discard_count === 1 && tmp_pilot_points.length >= first_discard_round){
                sub_totals[i].discards.push(tmp_pilot_points.pop());
            }
        }
        // Total up the remaining
        tot = 0;
        for (pp = 0; pp < tmp_pilot_points.length; pp+=1){
            tot += parseFloat(tmp_pilot_points[pp].points);
        }
        sub_totals[i].total = parseFloat(tot - pilot_penalties);
        sub_totals[i].penalties = pilot_penalties;
    }

    sub_totals.sort(compareSubtotalPoints);

    // calculate relative total score and rank
    var rank = 1;
    for (i=0; i<sub_totals.length; i+=1){
		sub_totals[i].percent = (sub_totals[i].total / sub_totals[0].total) * 100;
        sub_totals[i].rank = rank;
    	// check for tied score
    	if (i < sub_totals.length - 1) {
			if (i < sub_totals.length-1 && sub_totals[i].total !== sub_totals[i+1].total) {
				rank+=1;
			} else {
				switch (sub_totals[i].discards.length) {
				case 2:
					if (sub_totals[i].discards[1].points !== sub_totals[i+1].discards[1].points) {
						rank+=1;
						break;
					}
					break;
				case 1:
					if (sub_totals[i].discards[0].points !== sub_totals[i+1].discards[0].points) {
						rank+=1;
						break;
					}
					break;
				case 0:
					if (sub_totals[i].penalties !== sub_totals[i+1].penalties) {
						rank+=1;
						break;
					}
					break;
				}
			}
    	}
    }

    return sub_totals;
}

function createHeaderSpanScores(cellData){
	var span = document.createElement('span');
    $(span).addClass('span-header');
	$(span).html(cellData);
	return span;
}

function createTableListItemScores(cellData, rowIndex, colIndex, nameColIndex){
	var span;
	if (rowIndex >= 2) {
		if (colIndex >= 6) {
			if (colIndex === nameColIndex) {
				span = createSpan(cellData, 'span-left-italic');
			} else {
				span = createSpan(cellData, 'span-italic');
			}
		} else {
			if (colIndex === nameColIndex) {
				span = createSpan(cellData, 'span-left');
			} else {
				span = createSpan(cellData, 'span');
			}
		}
    } else {
        span = createHeaderSpanScores(cellData);
    }
	return span;
}

function createTableScores(tableData, nameColIndex, num_discards) {
	if (tableData === "") {
	    return;
	}

    var table = document.createElement('table');
    var tableBody = document.createElement('tbody');
    var rowIndexScores;
    var colIndexScores;
    var row;
    var cell;
    for (rowIndexScores=0; rowIndexScores<tableData.length; rowIndexScores+=1) {
        row = document.createElement('tr');
        for(colIndexScores=0 ; colIndexScores < tableData[rowIndexScores].length; colIndexScores+=1) {
            cell = document.createElement('td');
            if (rowIndexScores === 0) {
            	if (colIndexScores === 0) {
					$(cell).attr('colspan', 7 + num_discards);
                } else {
					$(cell).attr('colspan', 5);
                }
				$(cell).addClass('td-border-left');
            }
            if (rowIndexScores >= 1) {
                if (colIndexScores >= 7 && ((colIndexScores - num_discards - 7) % 5) === 0) {
                    $(cell).addClass('td-border-left');
                }
            }
            if (rowIndexScores === 1) {
                $(cell).addClass('td-border-bottom');
            }
            cell.appendChild(createTableListItemScores(tableData[rowIndexScores][colIndexScores], rowIndexScores, colIndexScores, nameColIndex));
            row.appendChild(cell);
        }
        tableBody.appendChild(row);
    }

    table.appendChild(tableBody);
    return table;
}

function render_scores(){
    var last_complete_round = model.current_round - 1;
    if (model.race_status == 2) {
        last_complete_round = model.current_round;
    }

	var first_discard_round = 4;
	var second_discard_round = 15;
	var num_discards = (last_complete_round>=first_discard_round) ? ((last_complete_round>=second_discard_round) ? 2 : 1) : 0;

	$('#race-name h2').html(model.race_name+" - Overall Standings <br>(Completed Rounds: "+last_complete_round+", Discarded Rounds: "+num_discards+")");

	var list = $('div.scores');
    var lis = [];

    var cols1 = [];
    var cols2 = [];
    var cols3 = [];

	var rows1 = [];

	if (last_complete_round < 1){
		$(list).html("<h3>No rounds completed yet</h3>");
		return;
	}

	var model_pilots_index_map = new Map();
	var i = 0;
	for (i=0; i < model.pilots.length; i+=1) {
		model_pilots_index_map.set(model.pilots[i].id, i);
	}

	// Add html elements
	// get final score
	var sub_totals_final = subTotals(last_complete_round);

	cols1.push("Total");
	cols2.push("Place");
	cols2.push("Id");
	cols2.push("Name");
	cols2.push("Team");
	cols2.push("Points");
	cols2.push("%");
	cols2.push("Penalty Points");
	var d;
	for (d = num_discards; d > 0 ; d-=1) {
	    cols2.push("Discard"+d);
	}

    var sub_totals_round;
    var sub_totals_pilots_index_map;
    var sub_totals_pilot_index;
    var found_pilot;
    var group_index;
    var pilot_index;
    var round;
    var p;
    var undefinedRow;
    var pilot_status;
    var pilot_group;
    var pilot_time;
    var pilot_flown;
    var pilot_penalties;
    var pilot_points;
    for (round = 0; round<last_complete_round; round+=1) {
        cols1.push("Round " + (round+1));

        cols2.push("Group");
        cols2.push("Time");
        cols2.push("Penalty Points");
        cols2.push("Points");
        if (round === 0) {
            cols2.push("Place (R1)");
        }
        else if (round === 1) {
            cols2.push("Place (R1,R2)");
        }
        else if (round === last_complete_round-1) {
            cols2.push("Place");
        }
        else {
            cols2.push("Place (R1-R" + (round+1) + ")");
        }
        //cols2.push("Sum");

		if (round < last_complete_round) {
        	sub_totals_round = subTotals(round+1);
			sub_totals_pilots_index_map = new Map();
			for (i=0; i < sub_totals_round.length; i+=1) {
				sub_totals_pilots_index_map.set(sub_totals_round[i].pilot_id, i);
			}
		}

		for (p = 0; p < sub_totals_final.length; p+=1) {
			found_pilot = 0;
			for (group_index = 0; group_index < model.racetimes[round].length; group_index+=1) {
				for (pilot_index = 0; pilot_index < model.racetimes[round][group_index].length; pilot_index+=1){
					if (model.racetimes[round][group_index][pilot_index].id == sub_totals_final[p].pilot_id) {
						found_pilot+=1;
						undefinedRow = 0;
						if (rows1[p] === undefined) {
							cols3 = [];
							undefinedRow = 1;
						} else {
							cols3 = rows1[p];
						}
                        pilot_status = model.pilots[model_pilots_index_map.get(model.racetimes[round][group_index][pilot_index].id)].status;
						if (model.racetimes[round][group_index][pilot_index].group == "0") {
							pilot_group = "-";
						} else {
							pilot_group = model.racetimes[round][group_index][pilot_index].group;
						}
						cols3.push(pilot_group);
						pilot_flown = model.racetimes[round][group_index][pilot_index].flown;
						if (pilot_flown == "0" || pilot_status == "4") {
							pilot_time = "-";
						} else {
							pilot_time = model.racetimes[round][group_index][pilot_index].time + " s";
						}
						cols3.push(pilot_time);
						pilot_penalties = model.racetimes[round][group_index][pilot_index].penalty * 100;
						pilot_penalties = pilot_penalties === 0 ? "-" : -pilot_penalties;
						cols3.push(pilot_penalties);
						pilot_points = parseFloat(model.racetimes[round][group_index][pilot_index].points);
						cols3.push(pilot_points.toFixed(2));
						if (round <= last_complete_round) {
		                    sub_totals_pilot_index = sub_totals_pilots_index_map.get(String(sub_totals_final[p].pilot_id));
							cols3.push(sub_totals_round[sub_totals_pilot_index].rank);
							//cols3.push(sub_totals_round[sub_totals_pilot_index].total.toFixed(2));
						}

						if (undefinedRow === 1) {
							rows1.push(cols3);
						} else if (round === 0) {
							rows1.push(cols3);
						}
					}
				}
			}
			if (found_pilot === 0) {
				cols3 = rows1[p];
				cols3.push("");
				cols3.push("");
				cols3.push("");
				cols3.push("");
				//cols3.push("");
				cols3.push("");
			}
		}
    }

	// set discards style
	for (p = 0; p < sub_totals_final.length; p+=1) {
		for (d = 0; d < sub_totals_final[p].discards.length; d+=1) {
			if (rows1[p].length - 5 > 0) {
				rows1[p][(sub_totals_final[p].discards[d].round_id * 5) + 3] = '<s>' + parseFloat(sub_totals_final[p].discards[d].points).toFixed(2) + '</s>';
			}
		}
	}

	// add header and overall scores
	p = 0;
	var discard_points;
	var row;
	for (row = 0; row<rows1.length; row+=1){
        cols3 = rows1[row];
		if (sub_totals_final[p] !== undefined) {
			if (sub_totals_final[p].discards !== undefined) {
				for (d = sub_totals_final[p].discards.length - 1; d >= 0 ; d-=1) {
					discard_points = parseFloat(sub_totals_final[p].discards[d].points);
					cols3.unshift(discard_points.toFixed(2));
				}
			}
			if (sub_totals_final[p].penalties === 0) {
			    cols3.unshift("-");
			}
			else {
			    cols3.unshift("-" + sub_totals_final[p].penalties);
			}
			cols3.unshift(sub_totals_final[p].percent.toFixed(2));
			cols3.unshift(sub_totals_final[p].total.toFixed(2));
			cols3.unshift(sub_totals_final[p].team);
			cols3.unshift(sub_totals_final[p].pilot_name);
			cols3.unshift(sub_totals_final[p].pilot_id);
			cols3.unshift(sub_totals_final[p].rank);
        	p+=1;
		}
    }
	rows1.unshift(cols2);
	rows1.unshift(cols1);

    var table1 = createTableScores(rows1, 2, num_discards);
    lis.push(table1);

	$(list).empty();
    $(list).html(lis);
}
