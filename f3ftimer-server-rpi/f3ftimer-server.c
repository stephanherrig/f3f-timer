#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <mqueue.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>
#ifndef DEBUG
#include <asoundlib.h>
#include <pcm.h>
#endif

/************** defines **************/
#define GPIO_BASE_OFFSET  (0x00200000) /* GPIO controller */
#define BLOCK_SIZE        (4*1024)
#define GPSET0    7
#define GPSET1    8
#define GPCLR0    10
#define GPCLR1    11
#define GPLEV0    13
#define GPLEV1    14
#define GPPUD     37
#define GPPUDCLK0 38
#define GPPUDCLK1 39

#define SOCKET_MQ_NAME     "/f3ftimer_send_mq"
#define MAX_MSG_SIZE    1024

#define BAUDRATE B4800
#define UART "/dev/ttyUSB0"

#define SOUND_MQ_NAME      "/f3ftimer_sound_mq"
#define BASE1_FILENAME     "/home/pi/DieAnlage/base1.wav"
#define BEEP_FILENAME      "/home/pi/DieAnlage/beep.wav"
#define SILENCE_FILENAME   "/home/pi/DieAnlage/silence1s.wav"

#define SPEECH_MQ_NAME     "/f3ftimer_speech_mq"

/************** type definitions **************/
typedef struct _thread_data_t {
    int sid;
} thread_data_t;

/************** variable definitions **************/

static int gpio_mem_fd;
static void *gpio_map;
static volatile uint32_t *gpio_base;

static mqd_t sound_mq;

static struct timespec start_flight_time;
static long long flight_time;
static int leg;
static int isAtA;

static timer_t climbout_timer_id = NULL;
static int model_launched = 0;
static int climbout_time_started = 0;
static int flight_time_started = 0;
static int off_course = 0;
static int on_course = 0;
static int legs = 10;

static int wind_debug_mode = 0;

/************** function definitions **************/

void error(char *msg) {
    printf("%s (%d - %s)\n", msg, errno, strerror(errno));
}

int mq_send_msg(int mq, char* sendbuf, int len, int maxMsgInQueue) {
    int i_error = 0;
    struct mq_attr mqstat;

    if (-1 == mq_getattr(mq, &mqstat)) {
        i_error = -1;
        error("ERROR mq_getattr failed\n");
    } else if (mqstat.mq_curmsgs > maxMsgInQueue) {
        i_error = -1;
        //error("ERROR more than maxMsgInQueue messages in mq\n");
    } else if (-1 == mq_send(mq, sendbuf, len, 0)) {
        i_error = -1;
        //error("ERROR mq_send failed\n");
    }

    return i_error;
}

void statemachine_reset() {
    leg = 0;
    isAtA = 0;
    flight_time = 0;
    flight_time_started = 0;
    model_launched = 0;
    climbout_time_started = 0;
    on_course = 0;
    off_course = 0;
}

void flight_time_start() {
    clock_gettime(CLOCK_MONOTONIC, &start_flight_time);
    flight_time_started = 1;
    printf("flight time started\n");
}

void flight_time_stop() {
    struct timespec end_flight_time;
    long long time1, time2, time_diff;

    clock_gettime(CLOCK_MONOTONIC, &end_flight_time);

    time1 = (start_flight_time.tv_sec * 1000000000LL) + start_flight_time.tv_nsec;
    time2 = (end_flight_time.tv_sec * 1000000000LL) + end_flight_time.tv_nsec;
    time_diff = time2 - time1;
    if (time_diff > 0) {
        flight_time = time_diff;
    }
}

void climbout_time_ended(union sigval sig) {
    if (flight_time_started != 1) {
        printf("climb out time ended\n");
        flight_time_start();
    }
}

void climbout_time_timer_start() {
    struct sigevent evp;
    struct itimerspec value;

    evp.sigev_notify = SIGEV_THREAD;
    evp.sigev_value.sival_ptr = &climbout_timer_id;
    evp.sigev_notify_function = climbout_time_ended;
    evp.sigev_notify_attributes = NULL;

    timer_create (CLOCK_MONOTONIC, &evp, &climbout_timer_id);

    value.it_value.tv_sec = 30;
    value.it_value.tv_nsec = 0;
    value.it_interval.tv_sec = 0;
    value.it_interval.tv_nsec = 0;
    timer_settime (climbout_timer_id, 0, &value, NULL);

    climbout_time_started = 1;
}

void climbout_time_timer_stop() {
    struct itimerspec value;

    if (climbout_timer_id != NULL) {
        value.it_value.tv_sec = 0;
        value.it_value.tv_nsec = 0;
        value.it_interval.tv_sec = 0;
        value.it_interval.tv_nsec = 0;
        timer_settime (climbout_timer_id, 0, &value, NULL);
        climbout_timer_id = NULL;
    }
}

void *socket_receive_loop(void *arg) {
    thread_data_t *data = (thread_data_t *) arg;
    mqd_t mq;
    struct mq_attr attr;
    int n;
    int prevType;
    char buffer[MAX_MSG_SIZE];

    pthread_setcanceltype(PTHREAD_CANCEL_ASYNCHRONOUS, &prevType);

    if (-1 != (mq = mq_open(SPEECH_MQ_NAME, O_CREAT | O_WRONLY, 0644, &attr))) {
        bzero(buffer, MAX_MSG_SIZE);
        printf("Starting receive loop (tid: %d, sid: %d)\n", pthread_self(), data->sid);
        while (1) {
            n = read(data->sid, buffer, MAX_MSG_SIZE);
            if (n > 0) {
                buffer[n] = 0;
                if (n > 1) {
                    //printf("received \"%s\"\n", buffer);
                } else {
                    //printf("received alive\n");
                }
                switch (buffer[0]) {
                case 'C':
                    printf("Cancel\n");
                    statemachine_reset();
                    climbout_time_timer_stop();
                    break;
                case 'S':
                    printf("Start\n");
                    statemachine_reset();
                    model_launched = 1;
                    if (climbout_time_started != 1) {
                        printf("Model launched\n");
                        climbout_time_timer_stop();
                        climbout_time_timer_start();
                    }
                    break;
				case 'L':
					legs = strtol(&buffer[1], NULL, 10);
					printf("Legs=%d\n", legs);
					break;
                case 'X':
                    printf("Speak \"%s\"\n", buffer);
                    if (-1 == mq_send_msg(mq, buffer, strlen(buffer)+1, 9)) {
                        //error("ERROR mqSend failed\n");
                    }
                    break;
                }
                bzero(buffer, MAX_MSG_SIZE);
            }
            else if (n == 0) {
                // do nothing
            }
            else {
                if (errno != EAGAIN) {
                    error("ERROR reading from socket\n");
                    break;
                }
            }
        }
        mq_close(mq);
    } else {
        error("ERROR mq_open failed\n");
    }

    printf("Exited receive loop\n");
    pthread_exit(NULL);
}

void *socket_send_loop(void *arg) {
    thread_data_t *data = (thread_data_t *) arg;
    int n;
    int prevType;
    mqd_t mq;
    struct mq_attr attr;
    char buffer[MAX_MSG_SIZE];
    ssize_t bytes_read;

    pthread_setcanceltype(PTHREAD_CANCEL_ASYNCHRONOUS, &prevType);

    attr.mq_flags = 0;
    attr.mq_maxmsg = 10;
    attr.mq_msgsize = MAX_MSG_SIZE;
    attr.mq_curmsgs = 0;

    if (-1 != (mq = mq_open(SOCKET_MQ_NAME, O_CREAT | O_RDONLY, 0644, &attr))) {
        printf("Starting send loop (tid: %d, sid: %d)\n", pthread_self(), data->sid);

        while (1) {
            memset(buffer, 0, MAX_MSG_SIZE);
            bytes_read = mq_receive(mq, buffer, MAX_MSG_SIZE, NULL);
            if (bytes_read >= 0) {
				buffer[bytes_read] = 0;
#ifdef DEBUG
                if (buffer[0] != 0) {
                    printf("send \"%s\"\n", buffer);
                }
#endif
                n = write(data->sid, &buffer, bytes_read+1);

                if (n < 0) {
                    error("ERROR writing to socket\n");
                    break;
                }
            } else {
                if (errno != EAGAIN) {
                    error("ERROR mq_receive failed\n");
                    break;
                }
            }
        }
        mq_close(mq);
    } else {
        error("ERROR mq_open failed\n");
    }

    printf("Exited send loop\n");
    pthread_exit(NULL);
}

#ifndef DEBUG
void gpio_delay_us(uint32_t delay) {
    int i;
    struct timespec tv_req;
    struct timespec tv_rem;
    uint32_t del_ms, del_us;
    del_ms = delay / 1000;
    del_us = delay % 1000;
    for (i = 0; i <= del_ms; i++) {
        tv_req.tv_sec = 0;
        if (i == del_ms)
            tv_req.tv_nsec = del_us * 1000;
        else
            tv_req.tv_nsec = 1000000;
        tv_rem.tv_sec = 0;
        tv_rem.tv_nsec = 0;
        nanosleep(&tv_req, &tv_rem);
        if (tv_rem.tv_sec != 0 || tv_rem.tv_nsec != 0)
            printf("timer oops!\n");
    }
}

/*
 * type:
 *   0 = no pull
 *   1 = pull down
 *   2 = pull up
 */
int gpio_set_pull(int gpio, int type) {
    if (gpio < 0 || gpio > 53)
        return -1;
    if (type < 0 || type > 2)
        return -1;

    if (gpio < 32) {
        *(gpio_base + GPPUD) = type;
        gpio_delay_us(10);
        *(gpio_base + GPPUDCLK0) = 0x1 << gpio;
        gpio_delay_us(10);
        *(gpio_base + GPPUD) = 0;
        gpio_delay_us(10);
        *(gpio_base + GPPUDCLK0) = 0;
        gpio_delay_us(10);
    } else {
        gpio -= 32;
        *(gpio_base + GPPUD) = type;
        gpio_delay_us(10);
        *(gpio_base + GPPUDCLK1) = 0x1 << gpio;
        gpio_delay_us(10);
        *(gpio_base + GPPUD) = 0;
        gpio_delay_us(10);
        *(gpio_base + GPPUDCLK1) = 0;
        gpio_delay_us(10);
    }
}

uint32_t gpio_get_hwbase(void) {
    const char *ranges_file = "/proc/device-tree/soc/ranges";
    uint8_t ranges[8];
    FILE *fd;
    uint32_t ret = 0;

    if ((fd = fopen(ranges_file, "rb")) == NULL) {
        printf("Can't open '%s'\n", ranges_file);
    } else {
        ret = fread(ranges, 1, sizeof(ranges), fd);

        if (ret == sizeof(ranges)) {
            ret = (ranges[4] << 24) | (ranges[5] << 16) | (ranges[6] << 8) | (ranges[7] << 0);
            if ((ranges[0] != 0x7e) || (ranges[1] != 0x00) || (ranges[2] != 0x00) || (ranges[3] != 0x00) || ((ret != 0x20000000) && (ret != 0x3f000000))) {
                printf("Unexpected ranges data (%02x%02x%02x%02x %02x%02x%02x%02x)\n", ranges[0], ranges[1], ranges[2], ranges[3], ranges[4], ranges[5], ranges[6], ranges[7]);
                ret = 0;
            }
        } else {
            printf("Can't read '%s'\n", ranges_file);
            ret = 0;
        }
    }

    fclose(fd);

    return ret;
}

int gpio_get_level(int gpio) {
    if (gpio < 0 || gpio > 53)
        return -1;
    int v;
    if (gpio < 32) {
        v = ((*(gpio_base + GPLEV0)) >> gpio) & 0x1;
    } else {
        gpio = gpio - 32;
        v = ((*(gpio_base + GPLEV1)) >> gpio) & 0x1;
    }
    v = 1 - v;
    return v;
}
#endif

void gpio_init() {
#ifndef DEBUG
    /* open /dev/mem */
    if ((gpio_mem_fd = open("/dev/mem", O_RDWR | O_SYNC | O_CLOEXEC)) < 0) {
        error("open /dev/mem failed\n");
        exit(EXIT_FAILURE);
    }

    uint32_t hwbase = gpio_get_hwbase();

    if (!hwbase) {
        exit(EXIT_FAILURE);
    }
    /* mmap GPIO */
    gpio_map = mmap(NULL,                    // Any address in our space will do
    BLOCK_SIZE,                 // Map length
    PROT_READ | PROT_WRITE, // Enable reading & writting to mapped memory
    MAP_SHARED,                 // Shared with other processes
    gpio_mem_fd,                     // File to map
    GPIO_BASE_OFFSET + hwbase   // Offset to GPIO peripheral
    );

    close(gpio_mem_fd); //No need to keep mem_fd open after mmap

    if (gpio_map == MAP_FAILED) {
        error("mmap failed\n");
        exit(EXIT_FAILURE);
    }

    // Always use volatile pointer!
    gpio_base = (volatile unsigned *) gpio_map;


    // using the following pins
    // BCM PINS 22, 23, 24, 27, (17, 18)
    // HW PINS  15, 16, 18, 13, (11, 12)
    gpio_set_pull(17, 2);
    gpio_set_pull(18, 2);
    gpio_set_pull(22, 2);
    gpio_set_pull(23, 2);
    gpio_set_pull(24, 2);
    gpio_set_pull(27, 2);
#endif
}

void *gpio_loop(void *arg) {
    int fd = 0;
    int value;
    int b1_state = 0;
    int b2_state = 0;
    int b3_state = 0;
    int b4_state = 0;
    int b5_state = 0;
    int b6_state = 0;
    mqd_t mq;
    struct mq_attr attr;
    fd_set readfds;

    attr.mq_flags = 0;
    attr.mq_maxmsg = 10;
    attr.mq_msgsize = MAX_MSG_SIZE;
    attr.mq_curmsgs = 0;

    if (-1 != (mq = mq_open(SOCKET_MQ_NAME, O_CREAT | O_WRONLY, 0644, &attr))) {
        printf("Starting gpio loop (tid: %d)\n", pthread_self());

        FD_ZERO(&readfds);
        FD_SET(fd, &readfds);

        gpio_init();

        while (1) {
#ifdef DEBUG
			usleep(100000);
#else
            usleep(10);
            // push button Start
            value = gpio_get_level(17);
            if (value != 0 && b1_state == 0) {
                b1_state = 1;
                button_S(mq);
            } else if (0 == value && b1_state != 0) {
                b1_state = 0;
            }
            // push button turn A
            value = gpio_get_level(18);
            if (value != 0 && b2_state == 0) {
                b2_state = 1;
                button_A(mq);
            } else if (0 == value && b2_state != 0) {
                b2_state = 0;
            }
            // push button turn B
            value = gpio_get_level(22);
            if (value != 0 && b3_state == 0) {
                b3_state = 1;
                button_B(mq);
            } else if (0 == value && b3_state != 0) {
                b3_state = 0;
            }
            // push button cancel
            value = gpio_get_level(23);
            if (value != 0 && b4_state == 0) {
                b4_state = 1;
                button_C(mq);
            } else if (0 == value && b4_state != 0) {
                b4_state = 0;
            }
            // push button penalty
            value = gpio_get_level(24);
            if (value != 0 && b5_state == 0) {
                b5_state = 1;
                button_P(mq);
            } else if (value == 0 && b5_state != 0) {
                b5_state = 0;
            }
            // push button cancel + zero points + next pilot
            value = gpio_get_level(27);
            if (value != 0 && b6_state == 0) {
                b6_state = 1;
                button_Z(mq);
            } else if (0 == value && b6_state != 0) {
                b6_state = 0;
            }
#endif
        }
    } else {
        error("ERROR mq_open failed\n");
    }

    mq_close(mq);
    pthread_exit(NULL);
}

void *debug_input_loop(void *arg) {
    int fd;
    char buffer[MAX_MSG_SIZE];
    mqd_t mq;
    struct mq_attr attr;
    fd_set readfds;

    attr.mq_flags = 0;
    attr.mq_maxmsg = 10;
    attr.mq_msgsize = MAX_MSG_SIZE;
    attr.mq_curmsgs = 0;

    if (-1 != (mq = mq_open(SOCKET_MQ_NAME, O_CREAT | O_WRONLY, 0644, &attr))) {
        printf("Starting debug input loop (tid: %d)\n", pthread_self());

        fd = 0; // stdin

        FD_ZERO(&readfds);
        FD_SET(fd, &readfds);

        while (1) {
            int count = select(fd + 1, &readfds, NULL, NULL, NULL);
            if (count > 0) {
                if (FD_ISSET(fd, &readfds)) {
                    if (NULL != fgets(buffer, MAX_MSG_SIZE, stdin)) {
                        switch (buffer[0]) {
                        case 'A':
                            button_A(mq);
                            break;
                        case 'B':
                            button_B(mq);
                            break;
                        case 'P':
                            button_P(mq);
                            break;
                        case 'S':
                            button_S(mq);
                            break;
                        case 'Z':
                            button_Z(mq);
                            break;
                        case 'C':
                            button_C(mq);
                            break;
                        case 'W':
                            switch(wind_debug_mode) {
                            case 0:
                                wind_debug_mode = 1;
                                break;
                            case 1:
                                wind_debug_mode = 2;
                                break;
                            case 2:
                                wind_debug_mode = 3;
                                break;
                            case 3:
                                wind_debug_mode = 4;
                                break;
                            case 4:
                                wind_debug_mode = 0;
                                break;
                            }
                            break;
                        }
                    }
                }
            }
        }
        mq_close(mq);
    } else {
        error("ERROR mq_open failed\n");
    }

    printf("Exited debug input loop\n");
    pthread_exit(NULL);
}

int button_C(mqd_t mq) {
    char buffer[3];

    printf("Cancel\n");
    statemachine_reset();
    climbout_time_timer_stop();
    memset(buffer, 0, 3);
    buffer[0] = 'C';
    buffer[1] = ' ';
    if (-1 == mq_send_msg(mq, buffer, strlen(buffer)+1, 9)) {
        //error("ERROR mqSend failed\n");
    }
    return 0;
}

int button_Z(mqd_t mq) {
    char buffer[3];

    printf("Cancel+Zero+Next\n");
    statemachine_reset();
    climbout_time_timer_stop();
    memset(buffer, 0, 3);
    buffer[0] = 'Z';
    buffer[1] = ' ';
    if (-1 == mq_send_msg(mq, buffer, strlen(buffer)+1, 9)) {
        //error("ERROR mqSend failed\n");
    }
    return 0;
}

int button_P(mqd_t mq) {
    char buffer[3];

    printf("Penalty\n");
    memset(buffer, 0, 3);
    buffer[0] = 'P';
    buffer[1] = ' ';
    if (-1 == mq_send_msg(mq, buffer, strlen(buffer)+1, 9)) {
        //error("ERROR mqSend failed\n");
    }
    sound_penalty();
    return 0;
}

int button_S(mqd_t mq) {
    char buffer[2];

    printf("Start\n");
    memset(buffer, 0, 3);
    buffer[0] = 'S';
    buffer[1] = ' ';
    if (-1 == mq_send_msg(mq, buffer, strlen(buffer)+1, 9)) {
        //error("ERROR mqSend failed\n");
    }
    return 0;
}

int button_B(mqd_t mq) {
    char buffer[3];

    printf("Turn B\n");
    if (isAtA == 1 && on_course == 1 && flight_time_started == 1) {
        leg++;
        printf("leg=%d\n", leg);
        sound_base1();
    }
    //printf("isAtA=%d leg=%d\n", isAtA, leg);
    isAtA = 0;
    memset(buffer, 0, 3);
    buffer[0] = 'B';
    buffer[1] = ' ';
    if (-1 == mq_send_msg(mq, buffer, strlen(buffer)+1, 9)) {
        //error("ERROR mqSend failed\n");
    }
    return 0;
}

int button_A(mqd_t mq) {
    char buffer[MAX_MSG_SIZE];

    printf("Turn A\n");
    if (isAtA == 0 && off_course == 0 && on_course == 1 && model_launched == 1 && climbout_time_started == 1) {
        leg++;
        printf("leg=%d\n", leg);
        sound_base1();
    }
    else if (off_course == 1 && on_course == 0 && model_launched == 1 && climbout_time_started == 1) {
        printf("On Course\n");
        climbout_time_timer_stop();
        if (flight_time_started != 1) {
            flight_time_start();
        }
        off_course = 0;
        on_course = 1;
        sound_base1();
    }
    else if (off_course == 0 && on_course == 0 && model_launched == 1 && climbout_time_started == 1) {
        off_course = 1;
        printf("Off Course\n");
        sound_base1();
    }
    //printf("isAtA=%d leg=%d\n", isAtA, leg);
    isAtA = 1;
    memset(buffer, 0, MAX_MSG_SIZE);
    buffer[0] = 'A';
    buffer[1] = ' ';
    if (-1 == mq_send_msg(mq, buffer, strlen(buffer)+1, 9)) {
        //error("ERROR mqSend failed\n");
    }
    if (leg == legs) {
        flight_time_stop();
        if (flight_time > 0) {
            memset(buffer, 0, MAX_MSG_SIZE);
            sprintf(buffer, "T%.2f ", flight_time / (double)1000000000LL);
            printf("%s\n", buffer);
            if (-1 == mq_send_msg(mq, buffer, strlen(buffer)+1, 19)) {
                //error("ERROR mqSend failed\n");
            }
        }
        statemachine_reset();
    }
    return 0;
}

int uart_init() {
#ifndef DEBUG
    int uartfd;
    struct termios options = { };

    uartfd = open(UART, O_RDONLY | O_NOCTTY);
    if (uartfd < 0) {
        error("ERROR open "UART" failed\n");
    } else {
        memset(&options, 0, sizeof(options));
        tcgetattr(uartfd, &options),
        options.c_cflag &= ~(PARENB | PARODD | HUPCL | CSTOPB | CSIZE | CRTSCTS); /* 8N1 no HW-Handshake */
        options.c_cflag |= CS8 | CLOCAL | CREAD;
        options.c_iflag &= ~(IXON | IXOFF | IXANY | ISTRIP);
        options.c_iflag |= IGNBRK | IGNPAR;
        options.c_oflag = 0;
        options.c_lflag = ~ICANON;
        options.c_cc[VTIME] = 3;
        options.c_cc[VMIN] = 255;
        cfsetspeed(&options, BAUDRATE);
        tcflush(uartfd, TCIFLUSH);
        tcsetattr(uartfd, TCSANOW, &options);
    }
    return uartfd;
#endif
}

void *uart_loop(void *arg) {
    int uartfd;
    char modified_buffer[MAX_MSG_SIZE];
    char* modify_buffer_pointer;
    char nmea_sentence[100];
    char sendbuf[100];
    mqd_t mq;
    struct mq_attr attr;
    fd_set readfds;
    int tokenIndex;
    double wind_angle;
    double wind_speed;
    char wind_reference;
    char wind_speed_unit;
    char wind_status;
    int wind_checksum;
    int j;

    attr.mq_flags = 0;
    attr.mq_maxmsg = 10;
    attr.mq_msgsize = MAX_MSG_SIZE;
    attr.mq_curmsgs = 0;

    if (-1 == (mq = mq_open(SOCKET_MQ_NAME, O_CREAT | O_WRONLY, 0644, &attr))) {
        error("ERROR mq_open failed\n");
        exit(EXIT_FAILURE);
    } else {
        printf("Starting uart loop (tid: %d)\n", pthread_self());
        uartfd = -1;
        FD_ZERO(&readfds);
        while(1) {
#ifdef DEBUG
            int wind_direction_offset;
            int wind_speed_offset;
            switch(wind_debug_mode) {
            case 0:
                break;
            case 1:
                wind_direction_offset = 0;
                wind_speed_offset = 30;
                break;
            case 2:
                wind_direction_offset = 100;
                wind_speed_offset = 0;
                break;
            case 3:
                wind_direction_offset = 100;
                wind_speed_offset = 30;
                break;
            case 4:
            default:
                wind_direction_offset = 0;
                wind_speed_offset = 0;
                break;
            }
            if (0 != wind_debug_mode) {
                memset(sendbuf, 0, 100);
                wind_angle = (rand() % 90) - 45 + wind_direction_offset;
                wind_speed = (rand() % 22) + 3 + wind_speed_offset;
                sprintf(sendbuf, "W,%f,%f ", wind_angle, wind_speed);
                if (-1 == mq_send_msg(mq, sendbuf, strlen(sendbuf)+1, 5)) {
                    //error("ERROR mq_send failed\n");
                }
            }
			usleep(500000);
#else
            if (uartfd < 0) {
                sleep(1);
                uartfd = uart_init();
                FD_ZERO(&readfds);
                FD_SET(uartfd, &readfds);
            }
            int count = select(uartfd+1, &readfds, NULL, NULL, NULL);
            if (count > 0 && FD_ISSET(uartfd, &readfds))
            {
                memset(modified_buffer, 0, sizeof(modified_buffer));
                int readlen = read(uartfd, modified_buffer, MAX_MSG_SIZE);
//                printf("uart read \"%s\" %d\n", modified_buffer, readlen);
                if (readlen >= 23 /* min NMEA MWV sentence length */)
                {
                    modify_buffer_pointer = strstr(modified_buffer, "$WIMWV");
                    if (0 != modify_buffer_pointer) {
                        // parse the NMEA sentence
                        // longest valid NMEA sentence is 82 characters long
                        memset(nmea_sentence, 0, sizeof(nmea_sentence));
                        strncpy(nmea_sentence, modify_buffer_pointer, sizeof(nmea_sentence));
                        // trim the line termination characters
                        char* line_term = strpbrk(nmea_sentence, "\r\n");
                        if (NULL != line_term) {
                            *line_term = 0;
                        }
//                        printf("found NMEA sentence: \"%s\"\n", nmea_sentence);
                        // parse the tokens of the NMEA MWV sentence
                        char *pch = strtok(modify_buffer_pointer, "$,*\r\n");
                        tokenIndex = 0;
                        wind_angle = 0;
                        wind_reference = 0;
                        wind_speed = 0;
                        wind_speed_unit = 0;
                        wind_status = 0;
                        wind_checksum = 0;
                        while (pch != NULL) {
                            switch (tokenIndex) {
                                case 0:
//                                    printf ("NMEA Tag=%s\n", pch);
                                    break;
                                case 1:
                                    wind_angle = atof(pch);
//                                    printf ("Wind Angle=%s (%f)\n", pch, wind_angle);
                                    break;
                                case 2:
                                    wind_reference = pch[0];
//                                    printf ("Reference=%s (%s)\n", pch, (wind_reference == 'T' ? "True" : "Relative"));
                                    break;
                                case 3:
                                    wind_speed = atof(pch);
//                                    printf ("Wind Speed=%s (%f)\n", pch, wind_speed);
                                    break;
                                case 4:
                                    wind_speed_unit = pch[0];
                                    // convert wind speed to m/s
                                    switch (wind_speed_unit) {
                                        case 'M':
//                                            printf ("Wind Speed Unit=%s (m/s) -> Wind Speed=%f m/s\n", pch, wind_speed);
                                            break;
                                        case 'K':
                                            wind_speed = wind_speed / 3.6;
//                                            printf ("Wind Speed Unit=%s (km/h) -> Wind Speed=%f m/s\n", pch, wind_speed);
                                            break;
                                        case 'N':
                                            wind_speed = wind_speed * 0.51444444;
//                                            printf ("Wind Speed Unit=%s (km/h) -> Wind Speed=%f m/s\n", pch, wind_speed);
                                            break;
                                    }
                                    break;
                                case 5:
                                    wind_status = pch[0];
                                    if (wind_status != 'A') {
                                        printf ("Wind Status=%s (Not Ok)\n", pch);
                                    }
                                    break;
                                case 6:
                                    sscanf(pch, "%x", &wind_checksum);
                                    unsigned char checksum = 0;
                                    int nmeastrlen = strlen(nmea_sentence);
                                    // calculate the checksum and verify the contained checksum
                                    for (j = 1; j < nmeastrlen; j++) {
                                        if (nmea_sentence[j] == '*') {
                                            break;
                                        }
                                        checksum ^= nmea_sentence[j];
                                    }
                                    if (wind_checksum != checksum) {
                                        printf ("Wind Checksum=%s (int=%X, calculated=%X)\n", pch, wind_checksum, checksum);
                                    } else if (wind_status == 'A') {
                                        // send wind data to f3ftimer
                                        memset(sendbuf, 0, 100);
                                        sprintf(sendbuf, "W,%f,%f ", wind_angle, wind_speed);
                                        if (-1 == mq_send_msg(mq, sendbuf, strlen(sendbuf)+1, 5)) {
                                            //error("ERROR mq_send failed\n");
                                        }
                                    }
                                    break;
                            }
                            pch = strtok(NULL, "$,*\r\n");
                            tokenIndex++;
                        }
                    }
                }
            }
#endif
        }
        mq_close(mq);
    }

    pthread_exit(NULL);
}

#ifndef DEBUG
int sound_play(char* filename) {
	int err;
	int fd = 0;
	unsigned char buf[1024];
	char *device = "default";
    snd_pcm_hw_params_t *hw_params;
	snd_pcm_uframes_t frames;
	snd_pcm_t *pcm_dev;

    if ((err = snd_pcm_open (&pcm_dev, device, SND_PCM_STREAM_PLAYBACK, 0)) < 0) {
        fprintf (stderr, "cannot open audio device %s (%s)\n", device, snd_strerror (err));
    }
    else
    {
        if ((err = snd_pcm_hw_params_malloc (&hw_params)) < 0) {
            fprintf (stderr, "cannot allocate hardware parameter structure (%s)\n", snd_strerror (err));
        }
        else if ((err = snd_pcm_hw_params_any (pcm_dev, hw_params)) < 0) {
            fprintf (stderr, "cannot initialize hardware parameter structure (%s)\n", snd_strerror (err));
        }
        else if ((err = snd_pcm_hw_params_set_access (pcm_dev, hw_params, SND_PCM_ACCESS_RW_INTERLEAVED)) < 0) {
            fprintf (stderr, "cannot set access type (%s)\n", snd_strerror (err));
        }
        else if ((err = snd_pcm_hw_params_set_format (pcm_dev, hw_params, SND_PCM_FORMAT_S16_LE)) < 0) {
            fprintf (stderr, "cannot set sample format (%s)\n", snd_strerror (err));
        }
        else
        {
            unsigned int rate = 44100;
            int dir = 0;
            if ((err = snd_pcm_hw_params_set_rate_near (pcm_dev, hw_params, &rate, &dir)) < 0) {
                fprintf (stderr, "cannot set sample rate (%s)\n", snd_strerror (err));
            }
            else
            {
                int channels = 2;
                if ((err = snd_pcm_hw_params_set_channels (pcm_dev, hw_params, channels)) < 0) {
                    fprintf (stderr, "cannot set channel count (%s)\n", snd_strerror (err));
                }
                else if ((err = snd_pcm_hw_params (pcm_dev, hw_params)) < 0) {
                    fprintf (stderr, "cannot set parameters (%s)\n", snd_strerror (err));
                }
                else
                {
                    snd_pcm_hw_params_get_period_size(hw_params, &frames, 0);

//                    int ptime;
//                    snd_pcm_hw_params_get_period_time(hw_params, &ptime, 0);

                    snd_pcm_hw_params_free (hw_params);


                    int buf_size = frames * channels * 2 /* 2 samples in buffer */;

                    if (1 > (fd = open (filename, O_RDONLY))) {
                        printf("open error\n");
                    }
                    else
                    {
                        long file_len = lseek(fd, 0, SEEK_END);
                        //long count = snd_pcm_format_size(hwparams->format, hwparams->rate * hwparams->channels);

                        // skip wav header and initial chunk for cleaner sound
                        file_len -= lseek(fd, 44 + buf_size, SEEK_SET);

                        long loop;
                        for (loop = 0; loop < file_len; loop += buf_size) {
                            if (1 > read (fd, buf, buf_size)) {
                                printf("read error\n");
                            }
                            else if ((err = snd_pcm_writei (pcm_dev, buf, frames)) < 0) {
                                fprintf (stderr, "write to audio interface failed (%s)\n", snd_strerror (err));
                                if ((err = snd_pcm_prepare (pcm_dev)) < 0) {
                                    fprintf (stderr, "cannot prepare audio interface for use (%s)\n", snd_strerror (err));
                                    snd_pcm_close(pcm_dev);
                                    break;
                                }
                            }
                        }
                        close(fd);

                        snd_pcm_drain(pcm_dev);
                    }
                }
            }
        }
        snd_pcm_close(pcm_dev);
    }

	return 0;
}
#endif

int sound_penalty() {
#ifndef DEBUG
    sound_enqueue(BEEP_FILENAME);
#endif
}

int sound_base1() {
#ifndef DEBUG
    sound_enqueue(BASE1_FILENAME);
#endif
}

int sound_silence() {
#ifndef DEBUG
    sound_enqueue(SILENCE_FILENAME);
#endif
}

int sound_enqueue(char* filename) {
    char buffer[MAX_MSG_SIZE];

    if (-1 == mq_send_msg(sound_mq, filename, strlen(filename)+1, MAX_MSG_SIZE)) {
        //error("ERROR mqSend failed\n");
    }
}

int sound_init() {
#ifndef DEBUG
    struct mq_attr attr;

    attr.mq_flags = 0;
    attr.mq_maxmsg = 10;
    attr.mq_msgsize = MAX_MSG_SIZE;
    attr.mq_curmsgs = 0;

    if (-1 == (sound_mq = mq_open(SOUND_MQ_NAME, O_CREAT | O_WRONLY, 0644, &attr))) {
        error("ERROR mq_open failed\n");
        exit(EXIT_FAILURE);
    }
#endif
    return 0;
}

void *sound_loop(void *arg) {
    thread_data_t *data = (thread_data_t *) arg;
    int n;
    int prevType;
    mqd_t mq;
    struct mq_attr attr;
    char buffer[MAX_MSG_SIZE];
    ssize_t bytes_read;

    pthread_setcanceltype(PTHREAD_CANCEL_ASYNCHRONOUS, &prevType);

    attr.mq_flags = 0;
    attr.mq_maxmsg = 10;
    attr.mq_msgsize = MAX_MSG_SIZE;
    attr.mq_curmsgs = 0;

    if (-1 != (mq = mq_open(SOUND_MQ_NAME, O_CREAT | O_RDONLY, 0644, &attr))) {
        printf("Starting sound loop (tid: %d)\n", pthread_self());
        sound_init();
        while (1) {
#ifdef DEBUG
            usleep(100000);
#else
            memset(buffer, 0, MAX_MSG_SIZE);
            bytes_read = mq_receive(mq, buffer, MAX_MSG_SIZE, NULL);
            if (bytes_read >= 0) {
                buffer[bytes_read] = 0;
                //printf("play sound file \"%s\"\n", buffer);
                sound_play(buffer);
            } else {
                error("ERROR mq_receive failed\n");
                break;
            }
#endif
        }
        mq_close(mq);
    } else {
        error("ERROR mq_open failed\n");
    }

    printf("Exited sound loop\n");
    pthread_exit(NULL);
}

void *keep_alive_loop(void *arg) {
    thread_data_t *data = (thread_data_t *) arg;
    int prevType;
    mqd_t mq;
    struct mq_attr attr;
    char buffer[MAX_MSG_SIZE];

    pthread_setcanceltype(PTHREAD_CANCEL_ASYNCHRONOUS, &prevType);

    attr.mq_flags = 0;
    attr.mq_maxmsg = 1;
    attr.mq_msgsize = MAX_MSG_SIZE;
    attr.mq_curmsgs = 0;

    if (-1 != (mq = mq_open(SOCKET_MQ_NAME, O_CREAT | O_WRONLY, 0644, &attr))) {
        printf("Starting alive loop (tid: %d)\n", pthread_self());
        memset(buffer, 0, MAX_MSG_SIZE);
        while (1) {
            if (-1 == mq_send(mq, buffer, 32, 0)) {
                //error("ERROR mqSend failed\n");
            }
            //sound_play(SILENCE_FILENAME);
            sleep(1);
        }
        mq_close(mq);
    } else {
        error("ERROR mq_open failed\n");
    }

    printf("Exited keep alive loop\n");
    pthread_exit(NULL);
}

void speak(char *lang, char *text) {
#ifndef DEBUG
    char cmd[MAX_MSG_SIZE];

    sprintf(cmd, "espeak -b 1 -z -v %s+f3 -k5 -s160 \"%s\" 2>/dev/null", lang, text);
    //printf("\"%s\"\n", cmd);
    system(cmd);
#endif
}

void *speech_loop(void *arg) {
    thread_data_t *data = (thread_data_t *) arg;
    int prevType;
    ssize_t bytes_read;
    mqd_t mq;
    struct mq_attr attr;
    char buffer[MAX_MSG_SIZE];
    char lang[3];

    pthread_setcanceltype(PTHREAD_CANCEL_ASYNCHRONOUS, &prevType);

    attr.mq_flags = 0;
    attr.mq_maxmsg = 1;
    attr.mq_msgsize = MAX_MSG_SIZE;
    attr.mq_curmsgs = 0;

    if (-1 != (mq = mq_open(SPEECH_MQ_NAME, O_CREAT | O_RDONLY, 0644, &attr))) {
        printf("Starting speech loop (tid: %d)\n", pthread_self());
        memset(buffer, 0, MAX_MSG_SIZE);
        while (1) {
#ifdef DEBUG
            usleep(100000);
#else
            memset(buffer, 0, MAX_MSG_SIZE);
            bytes_read = mq_receive(mq, buffer, MAX_MSG_SIZE, NULL);
            if (bytes_read >= 0) {
                buffer[bytes_read] = 0;
                lang[0] = buffer[1];
                lang[1] = buffer[2];
                lang[2] = 0;
                buffer[0] = ' ';
                buffer[1] = ' ';
                buffer[2] = ' ';
                speak(lang, buffer);
            } else {
                error("ERROR mq_receive failed\n");
                break;
            }
#endif
        }
        mq_close(mq);
    } else {
        error("ERROR mq_open failed\n");
    }

    printf("Exited speech loop\n");
    pthread_exit(NULL);
}

int main(int argc, char *argv[]) {
    int sockfd, newsockfd, portno, clilen, rc;
    struct sockaddr_in serv_addr, cli_addr;
    struct linger x_opt;
    struct timeval timeout;
    thread_data_t socketthrdata;
    pthread_t socketsendthr, socketrecvthr, gpiothr, uartthr, soundthr, speechthr, debugthr, keepalivethr;

//    if (argc < 2) {
//        error("ERROR, no port provided\n");
//        exit(EXIT_FAILURE);
//    }
//    portno = atoi(argv[1]);

    mq_unlink(SOCKET_MQ_NAME);
    mq_unlink(SOUND_MQ_NAME);
    mq_unlink(SPEECH_MQ_NAME);

    signal(SIGPIPE, SIG_IGN);
    signal(SIGHUP, SIG_IGN);

    srand((unsigned int)time(NULL));

    // send live sign
    if ((rc = pthread_create(&keepalivethr, NULL, keep_alive_loop, NULL))) {
        error("pthread_create alive thread\n");
        exit(EXIT_FAILURE);
    }

    // Setup debug input thread
    if ((rc = pthread_create(&debugthr, NULL, debug_input_loop, NULL))) {
        error("pthread_create debug input thread\n");
        exit(EXIT_FAILURE);
    }

    // Setup speech thread
    if ((rc = pthread_create(&speechthr, NULL, speech_loop, NULL))) {
        error("pthread_create speech thread\n");
        exit(EXIT_FAILURE);
    }

    // Setup sound output to line out speaker
    if ((rc = pthread_create(&soundthr, NULL, sound_loop, NULL))) {
        error("pthread_create sound thread\n");
        exit(EXIT_FAILURE);
    }

    // Setup gpio for push buttons
    if ((rc = pthread_create(&gpiothr, NULL, gpio_loop, NULL))) {
        error("pthread_create gpio thread\n");
        exit(EXIT_FAILURE);
    }

    // Setup uart for wind board
    if ((rc = pthread_create(&uartthr, NULL, uart_loop, NULL))) {
        error("pthread_create uart thread\n");
        exit(EXIT_FAILURE);
    }

    // Setup socket connection to f3ftimer tablet
    portno = 1234;
    sockfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sockfd < 0) {
        error("ERROR opening socket");
        exit(EXIT_FAILURE);
    }
    if (setsockopt(sockfd, SOL_SOCKET, SO_REUSEADDR, &(int ) { 1 }, sizeof(int)) < 0) {
        error("setsockopt SO_REUSEADDR failed\n");
        exit(EXIT_FAILURE);
    }
    if (setsockopt(sockfd, IPPROTO_TCP, TCP_NODELAY, &(int ) { 1 }, sizeof(int)) < 0) {
        error("setsockopt TCP_NODELAY failed\n");
        exit(EXIT_FAILURE);
    }
    bzero((char *) &serv_addr, sizeof(serv_addr));
    serv_addr.sin_family = AF_INET;
    serv_addr.sin_addr.s_addr = INADDR_ANY;
    serv_addr.sin_port = htons(portno);
    if (bind(sockfd, (struct sockaddr *) &serv_addr, sizeof(serv_addr)) < 0) {
        error("ERROR on binding");
        exit(EXIT_FAILURE);
    }
    listen(sockfd, 5);
    clilen = sizeof(cli_addr);
    // handle client connections
    while (1) {
        newsockfd = accept(sockfd, (struct sockaddr *) &cli_addr, &clilen);
        if (newsockfd < 0) {
            error("ERROR on accept");
            continue;
        }
        printf("Client connected\n");
        if (setsockopt(newsockfd, IPPROTO_TCP, TCP_NODELAY, &(int ) { 1 }, sizeof(int)) < 0) {
            error("setsockopt TCP_NODELAY failed\n");
            exit(EXIT_FAILURE);
        }
/*        if (setsockopt (newsockfd, SOL_SOCKET, SO_KEEPALIVE, &(int){1}, sizeof(int)) < 0) {
            error("setsockopt SO_KEEPALIVE failed\n");
            exit(EXIT_FAILURE);
        }
        if (setsockopt(newsockfd, SOL_TCP, TCP_KEEPIDLE, &(int){1}, sizeof(int)) < 0) {
          error("setsockopt TCP_KEEPIDLE failed\n");
            exit(EXIT_FAILURE);
        }
        if (setsockopt(newsockfd, SOL_TCP, TCP_KEEPINTVL, &(int){1}, sizeof(int)) < 0) {
          error("setsockopt TCP_KEEPINTVL failed\n");
            exit(EXIT_FAILURE);
        }
        if (setsockopt(newsockfd, SOL_TCP, TCP_KEEPCNT, &(int){1}, sizeof(int)) < 0) {
          error("setsockopt TCP_KEEPCNT failed\n");
            exit(EXIT_FAILURE);
        }*/
        timeout.tv_sec = 1;
        timeout.tv_usec = 0;
        if (setsockopt(newsockfd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) < 0) {
            error("setsockopt SO_SNDTIMEO failed\n");
            exit(EXIT_FAILURE);
        }
        timeout.tv_sec = 1;
        timeout.tv_usec = 0;
        if (setsockopt(newsockfd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) < 0) {
            error("setsockopt SO_RCVTIMEO failed\n");
            exit(EXIT_FAILURE);
        }
        x_opt.l_onoff = 0;
        x_opt.l_linger = 0;
        if (setsockopt(sockfd, SOL_SOCKET, SO_LINGER, &x_opt, sizeof x_opt) < 0) {
            error("setsockopt SO_LINGER failed\n");
            exit(EXIT_FAILURE);
        }

        socketthrdata.sid = newsockfd;
        if ((rc = pthread_create(&socketsendthr, NULL, socket_send_loop, &socketthrdata))) {
            error("pthread_create socket send thread\n");
            exit(EXIT_FAILURE);
        }
        if ((rc = pthread_create(&socketrecvthr, NULL, socket_receive_loop, &socketthrdata))) {
            error("pthread_create socket receive thread\n");
            exit(EXIT_FAILURE);
        }

        while (pthread_kill(socketsendthr, 0) == 0 && pthread_kill(socketrecvthr, 0) == 0) {
            sleep(1);
        }

        pthread_cancel(socketrecvthr);
        pthread_cancel(socketsendthr);
        close(newsockfd);
        printf("Client disconnected\n");
    }
    close(sockfd);
    mq_unlink(SOCKET_MQ_NAME);
    mq_unlink(SOUND_MQ_NAME);
    mq_unlink(SPEECH_MQ_NAME);

    return 0;
}
