#!/bin/bash

#Line width:
w=3

#csv directory:
data="users"

#plots directory:
plots="plots"

mkdir "$plots"

#Generate user list:
ls -1 $data/*.csv | sed -e "s/^$data\///" -e "s/\.csv$//" > users.txt

#Loop through users:
while read u; do

	gnuplot -persist <<-EOFMarker
	set term png size 1024,768
	set output "$plots/$u.png"
	set datafile separator ','
	set yrange [0:]
	set title 'Resource Usage for $u'
	set style data fsteps
	set xlabel "Date"
	set timefmt "%s"
	set format x "%m/%d/%Y"
	set xdata time
	set xtics rotate
	set ylabel "Cores/RAM(GB)"
	plot '$data/$u.csv' using 1:(\$2) with lines title 'Cores allocated' lw $w, '' using 1:(\$4) with lines title 'Cores load' lw $w, '' using 1:(\$3) with lines title 'RAM allocated' lw $w, '' using 1:(\$5) with lines title 'RAM load' lw $w
	EOFMarker

done < users.txt

