f3ftimer-server: f3ftimer-server.c
	gcc -lrt -lpthread -lasound -I /usr/include/alsa -o f3ftimer-server f3ftimer-server.c

f3ftimer-server-dbg: f3ftimer-server.c
	gcc -lrt -lpthread -DDEBUG -o f3ftimer-server-dbg -DDEBUG f3ftimer-server.c	